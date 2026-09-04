package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for review-round fix 1: re-provisioning an already-provisioned
 * participant must not hand back a freshly generated password with a 201 as if it had
 * been set, CouchDB's PUT to an existing _users document without its current _rev
 * returns 409 and leaves the stored password untouched, so StewardResource must report
 * that honestly instead of claiming success with credentials that will never authenticate.
 */
class StewardResourceProvisioningIntegrationTest {

    private static final String BASE_URL = "http://localhost:5984";
    private static final StewardAuthenticator ALWAYS_STEWARD_S1 = header -> "s-1";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper cleanupMapper = new ObjectMapper();
    // Set by the test as it provisions a participant, cleaned up in cleanUp() below so
    // repeated runs against the shared dev CouchDB instance don't accumulate leftover
    // databases, users, and replication jobs.
    private String provisionedParticipantRef;

    @AfterEach
    void cleanUp() throws Exception {
        if (provisionedParticipantRef == null) {
            return;
        }
        var dbName = "participant-" + provisionedParticipantRef;
        deleteReplicationJob("replicate-" + dbName + "-to-benefits");
        http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + dbName))
                .header("authorization", adminAuth()).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        deleteCouchDbUser(provisionedParticipantRef);
    }

    @Test
    void reProvisioningAnExistingParticipantDoesNotClaimAFalsePassword() {
        var participantRef = "p-provision-test-" + System.nanoTime();
        provisionedParticipantRef = participantRef;
        var mapper = new ObjectMapper();
        var userProvisioner = new CouchDbUserProvisioner("http://localhost:5984", "admin", "benefits", mapper);
        var databaseProvisioner = new CouchDbParticipantDatabaseProvisioner("http://localhost:5984", "admin", "benefits", mapper);
        var replicationProvisioner = new CouchDbReplicationProvisioner("http://localhost:5984", "admin", "benefits", mapper);
        var resource = new StewardResource(new InMemoryStewardAuthorizationStore(), new InMemoryReleaseRequestStore(),
                ALWAYS_STEWARD_S1, userProvisioner, databaseProvisioner, replicationProvisioner);

        var first = resource.provisionParticipant(participantRef, "Basic irrelevant");
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(first.getEntity()).isInstanceOf(StewardResource.ProvisionedCredentials.class);
        var firstCredentials = (StewardResource.ProvisionedCredentials) first.getEntity();
        assertThat(firstCredentials.password()).isNotBlank();

        var second = resource.provisionParticipant(participantRef, "Basic irrelevant");
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(second.getEntity()).isInstanceOf(StewardResource.ProvisionStatus.class);
    }

    private void deleteReplicationJob(String jobId) throws Exception {
        var get = http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/_replicator/" + jobId))
                .header("authorization", adminAuth()).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (get.statusCode() != 200) {
            return;
        }
        var rev = cleanupMapper.readTree(get.body()).get("_rev").asText();
        http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/_replicator/" + jobId + "?rev=" + rev))
                .header("authorization", adminAuth()).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private void deleteCouchDbUser(String name) throws Exception {
        var docId = "org.couchdb.user:" + name;
        var get = http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/_users/" + docId))
                .header("authorization", adminAuth()).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (get.statusCode() != 200) {
            return;
        }
        var rev = cleanupMapper.readTree(get.body()).get("_rev").asText();
        http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/_users/" + docId + "?rev=" + rev))
                .header("authorization", adminAuth()).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String adminAuth() {
        return "Basic " + Base64.getEncoder().encodeToString("admin:benefits".getBytes());
    }
}
