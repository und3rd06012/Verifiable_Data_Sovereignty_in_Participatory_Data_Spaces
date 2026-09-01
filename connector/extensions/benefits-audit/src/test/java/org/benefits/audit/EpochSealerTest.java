package org.benefits.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EpochSealerTest {

    private AuditEntry entry(String actor) {
        return new AuditEntry(-1, null, "2026-08-31T00:00:00Z", "negotiation-finalized",
                actor, "asset-1", "purpose", "plain");
    }

    @Test
    void doesNotSealBeforeTheThreshold() {
        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 3);
        sealer.record(entry("a"));
        sealer.record(entry("b"));
        assertThat(store.epochChain()).isEmpty();
        assertThat(store.unsealedEntries()).hasSize(2);
    }

    @Test
    void sealsExactlyAtTheThresholdWithAllMemberEntries() {
        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 3);
        sealer.record(entry("a"));
        sealer.record(entry("b"));
        sealer.record(entry("c"));
        assertThat(store.epochChain()).hasSize(1);
        assertThat(store.unsealedEntries()).isEmpty();
        var sealed = store.entriesFor("a").get(0);
        assertThat(sealed.epoch()).isEqualTo(store.epochChain().get(0).epochId());
    }

    @Test
    void secondEpochChainsToTheFirstsRoot() {
        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 2);
        sealer.record(entry("a"));
        sealer.record(entry("b"));
        sealer.record(entry("c"));
        sealer.record(entry("d"));
        assertThat(store.epochChain()).hasSize(2);
        var first = store.epochChain().get(0);
        var second = store.epochChain().get(1);
        assertThat(first.prevEpochHash()).isEqualTo(AuditHashing.GENESIS_PREV_EPOCH_HASH);
        assertThat(second.prevEpochHash())
                .isEqualTo(AuditHashing.prevEpochHash(first.epochId(), first.merkleRoot(), first.sealedAt()));
    }

    @Test
    void sealedEntriesVerifyAgainstTheirEpochsRoot() {
        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 4);
        for (var i = 0; i < 4; i++) {
            sealer.record(entry("actor-" + i));
        }
        var epoch = store.epochChain().get(0);
        var leafHashes = new java.util.ArrayList<String>();
        for (var i = 0; i < 4; i++) {
            leafHashes.add(AuditHashing.leafHash(store.entriesFor("actor-" + i).get(0)));
        }
        var proof = MerkleTree.proof(leafHashes, 0);
        assertThat(MerkleTree.verify(leafHashes.get(0), proof, epoch.merkleRoot())).isTrue();
    }
}
