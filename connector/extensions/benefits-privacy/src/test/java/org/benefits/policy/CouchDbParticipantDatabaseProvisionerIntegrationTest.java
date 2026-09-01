package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CouchDbParticipantDatabaseProvisionerIntegrationTest {

    private static final String BASE_URL = "http://localhost:5984";
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createsAnIsolatedDatabaseWithTheIdentityGateInstalled() throws Exception {
        var participantRef = "p-dbtest-" + System.nanoTime();
        var provisioner = new CouchDbParticipantDatabaseProvisioner(BASE_URL, "admin", "benefits", mapper);

        var outcome = provisioner.provision(participantRef);
        assertThat(outcome).isEqualTo(CouchDbParticipantDatabaseProvisioner.Outcome.CREATED);
        // provision() only creates the database/_security/design-doc -- the CouchDB user
        // itself is CouchDbUserProvisioner's job (Task 4 wires both together). Provision
        // one here directly so this test can authenticate as the real participant, not admin.
        provisionCouchDbUser(participantRef, "pw-own");

        var dbName = provisioner.databaseNameFor(participantRef);
        var ownAuth = "Basic " + Base64.getEncoder().encodeToString((participantRef + ":pw-own").getBytes());

        // The participant can reach their own database, with their own credentials.
        var ownRead = http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + dbName))
                .header("authorization", ownAuth).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(ownRead.statusCode()).isEqualTo(200);

        // A different participant cannot read this one's database.
        var otherRef = "p-dbtest-other-" + System.nanoTime();
        provisionCouchDbUser(otherRef, "otherpw");
        var otherAuth = "Basic " + Base64.getEncoder().encodeToString((otherRef + ":otherpw").getBytes());
        var otherRead = http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + dbName))
                .header("authorization", otherAuth).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(otherRead.statusCode()).isEqualTo(403);

        // Nor can the participant read the shared "benefits" database -- the exact
        // property this plan exists to guarantee. The "benefits" database already exists
        // on the CouchDB instance these tests run against (created by tools/lib.sh's
        // bootstrap, which every test in this module already assumes is in place).
        var sharedRead = http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/benefits"))
                .header("authorization", ownAuth).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(sharedRead.statusCode()).isEqualTo(403);

        // Re-provisioning the same participant is idempotent.
        var second = provisioner.provision(participantRef);
        assertThat(second).isEqualTo(CouchDbParticipantDatabaseProvisioner.Outcome.ALREADY_EXISTS);
    }

    @Test
    void aParticipantCannotWriteADocumentClaimingSomeoneElsesIdentity() throws Exception {
        var participantRef = "p-forge-test-" + System.nanoTime();
        var provisioner = new CouchDbParticipantDatabaseProvisioner(BASE_URL, "admin", "benefits", mapper);
        provisioner.provision(participantRef);
        provisionCouchDbUser(participantRef, "pw-forge");
        var dbName = provisioner.databaseNameFor(participantRef);
        var ownAuth = "Basic " + Base64.getEncoder().encodeToString((participantRef + ":pw-forge").getBytes());

        var forged = mapper.createObjectNode();
        forged.put("type", "contribution").put("participantRef", "someone-else");
        var response = http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + dbName + "/forged-doc"))
                .header("authorization", ownAuth).header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(forged))).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    private void provisionCouchDbUser(String name, String password) throws Exception {
        var body = mapper.createObjectNode();
        body.put("name", name).put("password", password).put("type", "user");
        body.putArray("roles");
        http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/_users/org.couchdb.user:" + name))
                .header("authorization", adminAuth()).header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String adminAuth() {
        return "Basic " + Base64.getEncoder().encodeToString("admin:benefits".getBytes());
    }
}
