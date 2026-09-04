package org.benefits.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.EdcException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

/**
 * Configures a continuous, one-way CouchDB replication job from a participant's own
 * database into the shared "benefits" database, via the admin-only "_replicator"
 * database, admin-credentialed on both source and target so the participant never
 * holds credentials to the shared database. CouchDbAuditStore/CouchDbContributionSource
 * keep reading every participant's writes out of that single shared database exactly
 * as before, only how a write gets there changes.
 *
 * A PUT of a job document that already exists (same jobId, unchanged or not) is
 * rejected by CouchDB with 409 because no _rev was supplied, that is treated as
 * "already provisioned", not a failure, making ensureReplication idempotent. Unlike
 * CouchDbUserProvisioner's password case this is not consequence-free: the job body
 * DOES change over time (the selector field below was added after some jobs already
 * existed), and this 409-tolerant idempotency means an existing job never picks up
 * such a change, ensureReplication only ever creates, it does not update. A job
 * created before a body change keeps its old shape indefinitely unless something
 * explicitly deletes and recreates it. Known limitation, recorded in
 * docs/superpowers/specs/2026-09-01-participant-database-isolation-design.md's
 * "Known limitations" section rather than left implicit here alone.
 */
public class CouchDbReplicationProvisioner {

    private final String baseUrl;
    private final String adminAuthHeader;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbReplicationProvisioner(String baseUrl, String adminUser, String adminPassword, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.adminAuthHeader = "Basic " + Base64.getEncoder().encodeToString((adminUser + ":" + adminPassword).getBytes());
        this.mapper = mapper;
    }

    public void ensureReplication(String sourceDb, String targetDb) {
        var jobId = "replicate-" + sourceDb + "-to-" + targetDb;
        var body = mapper.createObjectNode();
        var source = body.putObject("source");
        source.put("url", baseUrl + "/" + sourceDb);
        source.putObject("headers").put("Authorization", adminAuthHeader);
        var target = body.putObject("target");
        target.put("url", baseUrl + "/" + targetDb);
        target.putObject("headers").put("Authorization", adminAuthHeader);
        body.put("continuous", true);
        body.put("owner", "admin");
        // Excludes design documents from replication, otherwise a participant's copy of
        // _design/consent (installed idempotently and never re-applied once a database is
        // provisioned, per CouchDbParticipantDatabaseProvisioner) could overwrite the shared
        // database's copy on the next edit to consent-design-doc.json, turning validation
        // logic into a race between whichever participant databases replicate first. Verified
        // live against CouchDB 3.5.2: a Mango `selector` on the _replicator job document is
        // evaluated per document, so a non-design document still replicates while a
        // `_design/*` document does not.
        body.putObject("selector").putObject("_id").put("$regex", "^(?!_design/)");
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/_replicator/" + jobId))
                    .header("authorization", adminAuthHeader).header("content-type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            // Verified live against CouchDB 3.5.2: a new job document is accepted with 201,
            // and a repeat PUT of the same jobId without its current _rev is rejected with
            // 409 (the replicator manager never gets to see it), both are success here.
            if (response.statusCode() != 201 && response.statusCode() != 409) {
                throw new EdcException("failed to create replication job " + jobId + ": "
                        + response.statusCode() + " " + response.body());
            }
        } catch (JsonProcessingException e) {
            throw new EdcException("failed to serialize replication job body", e);
        } catch (IOException e) {
            throw new EdcException("failed to create replication job " + jobId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("interrupted creating replication job " + jobId, e);
        }
    }
}
