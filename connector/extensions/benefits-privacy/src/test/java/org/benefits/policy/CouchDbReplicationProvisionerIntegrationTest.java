package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CouchDbReplicationProvisionerIntegrationTest {

    private static final String BASE_URL = "http://localhost:5984";
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    // Populated by each test as it provisions databases/replication jobs, deleted in
    // cleanUp() below so repeated runs against the shared dev CouchDB instance don't
    // accumulate leftover databases and crashed replicator jobs.
    private final List<String> createdDatabases = new ArrayList<>();
    private final List<String> createdReplicationJobs = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (var jobId : createdReplicationJobs) {
            deleteReplicationJob(jobId);
        }
        for (var dbName : createdDatabases) {
            http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + dbName))
                    .header("authorization", adminAuth()).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void aDocumentWrittenToTheSourceEventuallyAppearsInTheTarget() throws Exception {
        var suffix = System.nanoTime();
        var sourceDb = "replicate-source-test-" + suffix;
        var targetDb = "replicate-target-test-" + suffix;
        createDatabase(sourceDb);
        createDatabase(targetDb);

        ensureReplication(sourceDb, targetDb);

        var docId = "doc-" + suffix;
        var body = mapper.createObjectNode().put("marker", "replication-test");
        var put = http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + sourceDb + "/" + docId))
                .header("authorization", adminAuth()).header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isEqualTo(201);

        assertReplicatedWithin(targetDb, docId, 30);
    }

    @Test
    void aDesignDocumentWrittenToTheSourceDoesNotReplicate() throws Exception {
        var suffix = System.nanoTime();
        var sourceDb = "replicate-source-design-" + suffix;
        var targetDb = "replicate-target-design-" + suffix;
        createDatabase(sourceDb);
        createDatabase(targetDb);

        ensureReplication(sourceDb, targetDb);

        // A normal document still replicates (same as the unfiltered case above)...
        var docId = "doc-" + suffix;
        var body = mapper.createObjectNode().put("marker", "replication-test");
        http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + sourceDb + "/" + docId))
                .header("authorization", adminAuth()).header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertReplicatedWithin(targetDb, docId, 30);

        // ...but a _design/* document (written here as admin, for test purposes, a
        // participant can never write one) does not.
        var designBody = mapper.createObjectNode();
        designBody.putObject("views");
        http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + sourceDb + "/_design/consent"))
                .header("authorization", adminAuth()).header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(designBody))).build(),
                HttpResponse.BodyHandlers.ofString());

        Thread.sleep(3000);
        var get = http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + targetDb + "/_design/consent"))
                .header("authorization", adminAuth()).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(get.statusCode()).isEqualTo(404);
    }

    @Test
    void ensuringReplicationTwiceIsIdempotent() throws Exception {
        var suffix = System.nanoTime();
        var sourceDb = "replicate-source-idem-" + suffix;
        var targetDb = "replicate-target-idem-" + suffix;
        createDatabase(sourceDb);
        createDatabase(targetDb);
        var provisioner = new CouchDbReplicationProvisioner(BASE_URL, "admin", "benefits", mapper);

        provisioner.ensureReplication(sourceDb, targetDb);
        // The second call must not throw, even though CouchDB rejects the repeat PUT
        // of the same job document with 409 (no _rev supplied).
        provisioner.ensureReplication(sourceDb, targetDb);
        createdReplicationJobs.add(jobIdFor(sourceDb, targetDb));
    }

    private void assertReplicatedWithin(String targetDb, String docId, int maxAttempts) throws Exception {
        for (var attempt = 1; attempt <= maxAttempts; attempt++) {
            var get = http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + targetDb + "/" + docId))
                    .header("authorization", adminAuth()).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (get.statusCode() == 200 && get.body().contains("replication-test")) {
                return;
            }
            if (attempt == maxAttempts) {
                assertThat(get.statusCode()).as("replicated document status after %d attempts", maxAttempts).isEqualTo(200);
                assertThat(get.body()).contains("replication-test");
            }
            Thread.sleep(500);
        }
    }

    private void createDatabase(String dbName) throws Exception {
        http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + dbName))
                .header("authorization", adminAuth()).PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        createdDatabases.add(dbName);
    }

    private void ensureReplication(String sourceDb, String targetDb) {
        new CouchDbReplicationProvisioner(BASE_URL, "admin", "benefits", mapper).ensureReplication(sourceDb, targetDb);
        createdReplicationJobs.add(jobIdFor(sourceDb, targetDb));
    }

    // Mirrors CouchDbReplicationProvisioner's own jobId naming so cleanup can find and
    // delete the job document it created.
    private String jobIdFor(String sourceDb, String targetDb) {
        return "replicate-" + sourceDb + "-to-" + targetDb;
    }

    private void deleteReplicationJob(String jobId) throws Exception {
        var get = http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/_replicator/" + jobId))
                .header("authorization", adminAuth()).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (get.statusCode() != 200) {
            return;
        }
        var rev = mapper.readTree(get.body()).get("_rev").asText();
        http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/_replicator/" + jobId + "?rev=" + rev))
                .header("authorization", adminAuth()).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String adminAuth() {
        return "Basic " + Base64.getEncoder().encodeToString("admin:benefits".getBytes());
    }
}
