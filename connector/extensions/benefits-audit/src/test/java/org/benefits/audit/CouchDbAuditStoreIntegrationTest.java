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
import java.util.List;
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
    // with each other, but a database this test creates is this test's to clean up -
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
    void entriesInEpochReturnsSealedMembersInSeqOrder() {
        store.append(new AuditEntry(1L, null, "t", "type", "b", "asset", "purpose", "plain"));
        store.append(new AuditEntry(2L, null, "t", "type", "a", "asset", "purpose", "plain"));
        var epoch = new Epoch("epoch-inEpoch-test", "root-value", AuditHashing.GENESIS_PREV_EPOCH_HASH, "t");
        store.sealEpoch(epoch, 1L, 2L);

        var members = store.entriesInEpoch("epoch-inEpoch-test");
        assertThat(members).extracting(AuditEntry::seq).containsExactly(1L, 2L);
        assertThat(members).allMatch(e -> "epoch-inEpoch-test".equals(e.epoch()));
    }

    @Test
    void nextSeqIsStrictlyIncreasing() {
        var first = store.nextSeq();
        var second = store.nextSeq();
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void incompleteEpochsResumesAfterASimulatedCrash() throws Exception {
        // Simulates a crash between sealEpoch's two phases: the auditEpoch document is
        // written (with the real, already-computed root), but the member entries are
        // never stamped. incompleteEpochs() must find this, and EpochSealer's
        // constructor must resume the stamping step using the SAME epoch id and root
        // already written, not recomputing anything.
        store.append(new AuditEntry(1L, null, "t", "type", "a", "asset", "purpose", "plain"));
        store.append(new AuditEntry(2L, null, "t", "type", "b", "asset", "purpose", "plain"));

        var leafHashes = List.of(
                AuditHashing.leafHash(new AuditEntry(1L, "epoch-crash-test", "t", "type", "a", "asset", "purpose", "plain")),
                AuditHashing.leafHash(new AuditEntry(2L, "epoch-crash-test", "t", "type", "b", "asset", "purpose", "plain")));
        var root = MerkleTree.root(leafHashes);

        // Write the epoch doc directly, bypassing sealEpoch's entry-stamping step --
        // simulating the crash window between the two phases.
        var doc = mapper.createObjectNode();
        doc.put("_id", "auditEpoch-epoch-crash-test");
        doc.put("type", "auditEpoch");
        doc.put("epochId", "epoch-crash-test");
        doc.put("merkleRoot", root);
        doc.put("prevEpochHash", AuditHashing.GENESIS_PREV_EPOCH_HASH);
        doc.put("sealedAt", "t");
        doc.put("firstSeq", 1L);
        doc.put("lastSeq", 2L);
        var auth = "Basic " + Base64.getEncoder().encodeToString("admin:benefits".getBytes());
        HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(BASE_URL + "/" + database + "/auditEpoch-epoch-crash-test"))
                        .header("authorization", auth).header("content-type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(doc))).build(),
                HttpResponse.BodyHandlers.discarding());

        var incomplete = store.incompleteEpochs();
        assertThat(incomplete).hasSize(1);
        assertThat(incomplete.get(0).epoch().epochId()).isEqualTo("epoch-crash-test");
        assertThat(incomplete.get(0).firstSeq()).isEqualTo(1L);
        assertThat(incomplete.get(0).lastSeq()).isEqualTo(2L);

        // Constructing a new EpochSealer against this same store simulates a restart --
        // its constructor calls resumeIncompleteSeals(), which must stamp the two
        // entries without recomputing the root.
        new EpochSealer(store, 100);

        assertThat(store.incompleteEpochs()).isEmpty();
        var resumedEntry = store.entriesFor("a").get(0);
        assertThat(resumedEntry.epoch()).isEqualTo("epoch-crash-test");
        assertThat(store.epochChain()).extracting(Epoch::epochId).containsExactly("epoch-crash-test");
        assertThat(store.epochChain().get(0).merkleRoot()).isEqualTo(root);
    }

    @Test
    void entriesForRoundTripsAnActorContainingQuotesAndBackslashes() {
        var tricky = "actor\\with\\backslash\"and\"quotes";
        store.append(new AuditEntry(1L, null, "t", "type", tricky, "asset", "purpose", "plain"));

        var entries = store.entriesFor(tricky);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).actor()).isEqualTo(tricky);
    }
}
