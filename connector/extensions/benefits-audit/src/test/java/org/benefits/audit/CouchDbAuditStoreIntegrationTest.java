package org.benefits.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Requires a running CouchDB at localhost:5984 with the credentials this repo's other
 * CouchDB integration tests use (see connector/runtime/provider.properties). Skips
 * itself rather than failing the build when CouchDB is not reachable, the same
 * convention benefits-privacy uses elsewhere in this repo.
 */
class CouchDbAuditStoreIntegrationTest {

    private static final String BASE_URL = "http://localhost:5984";
    private final String database = "benefits-audit-test-" + UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpClient client;
    private String auth;
    private CouchDbAuditStore store;
    private boolean created;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(couchDbReachable(), "CouchDB not reachable at " + BASE_URL);
        client = HttpClient.newHttpClient();
        auth = "Basic " + Base64.getEncoder().encodeToString("admin:benefits".getBytes());
        client.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + database))
                .header("authorization", auth).PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        created = true;
        store = new CouchDbAuditStore(BASE_URL, database, "admin", "benefits", mapper);
    }

    // Each test creates its own randomly-named database (above) so tests never collide
    // with each other, but a database this test creates is this test's to clean up —
    // otherwise every run of this suite leaves a permanent, never-reclaimed database on
    // whatever CouchDB instance it ran against.
    @AfterEach
    void tearDown() throws Exception {
        if (!created) {
            return;
        }
        client.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + database))
                .header("authorization", auth).DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }

    private boolean couchDbReachable() {
        try {
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(BASE_URL)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void appendedEntriesArePersistedAndReadableByActor() {
        var entry = new AuditEntry(1L, null, "2026-08-31T00:00:00Z", "negotiation-finalized",
                "participant-x", "asset-1", "purpose", "plain");
        store.append(entry);
        assertThat(store.unsealedEntries()).extracting(AuditEntry::actor).contains("participant-x");
        assertThat(store.entriesFor("participant-x")).hasSize(1);
    }

    @Test
    void sealEpochPersistsTheEpochAndStampsMemberEntries() {
        store.append(new AuditEntry(1L, null, "t", "type", "a", "asset", "purpose", "plain"));
        store.append(new AuditEntry(2L, null, "t", "type", "b", "asset", "purpose", "plain"));
        var epoch = new Epoch("epoch-test", "root-value", AuditHashing.GENESIS_PREV_EPOCH_HASH, "t");
        store.sealEpoch(epoch, 1L, 2L);

        assertThat(store.epochChain()).extracting(Epoch::epochId).containsExactly("epoch-test");
        assertThat(store.unsealedEntries()).isEmpty();
        assertThat(store.entriesFor("a").get(0).epoch()).isEqualTo("epoch-test");
    }

    @Test
    void nextSeqIsStrictlyIncreasing() {
        var first = store.nextSeq();
        var second = store.nextSeq();
        assertThat(second).isGreaterThan(first);
    }
}
