package org.benefits.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditResourceTest {

    private static final SessionAuthenticator ALWAYS_ALICE = header -> "alice";
    private static final SessionAuthenticator ALWAYS_MALLORY = header -> "mallory";
    private static final SessionAuthenticator ALWAYS_UNAUTHENTICATED = header -> null;

    @Test
    void returnsOnlyEntriesForTheRequestedParticipantWithAProofPerEntryAndTheFullEpochChain() {
        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 2);
        sealer.record(new AuditEntry(-1, null, "t1", "negotiation-finalized", "alice", "asset-1", "purpose", "plain-a"));
        sealer.record(new AuditEntry(-1, null, "t2", "negotiation-finalized", "bob", "asset-2", "purpose", "plain-b"));
        sealer.record(new AuditEntry(-1, null, "t3", "negotiation-finalized", "alice", "asset-3", "purpose", "plain-c"));

        var resource = new AuditResource(store, ALWAYS_ALICE);
        var httpResponse = resource.forParticipant("alice", "Basic irrelevant");
        assertThat(httpResponse.getStatus()).isEqualTo(200);
        var response = (AuditResource.AuditResponse) httpResponse.getEntity();

        assertThat(response.entries()).hasSize(2);
        assertThat(response.entries()).allMatch(e -> e.actor().equals("alice"));
        assertThat(response.proofs()).hasSize(1);
        assertThat(response.epochs()).hasSize(1);

        var sealedEntry = response.entries().stream().filter(e -> e.epoch() != null).findFirst().orElseThrow();
        var proof = response.proofs().get(sealedEntry.seq());
        var leaf = AuditHashing.leafHash(sealedEntry);
        var root = response.epochs().get(0).merkleRoot();
        assertThat(MerkleTree.verify(leaf, proof, root)).isTrue();
    }

    @Test
    void unsealedEntriesHaveNoProof() {
        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 100);
        sealer.record(new AuditEntry(-1, null, "t1", "negotiation-finalized", "alice", "asset-1", "purpose", "plain"));

        var resource = new AuditResource(store, ALWAYS_ALICE);
        var httpResponse = resource.forParticipant("alice", "Basic irrelevant");
        var response = (AuditResource.AuditResponse) httpResponse.getEntity();

        assertThat(response.entries()).hasSize(1);
        assertThat(response.proofs()).isEmpty();
    }

    @Test
    void rejectsARequesterWhoseAuthenticatedIdentityDoesNotMatchTheRequestedParticipant() {
        var store = new InMemoryAuditStore();
        var resource = new AuditResource(store, ALWAYS_MALLORY);

        var httpResponse = resource.forParticipant("alice", "Basic irrelevant");

        assertThat(httpResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void rejectsAMissingOrInvalidAuthorizationHeader() {
        var store = new InMemoryAuditStore();
        var resource = new AuditResource(store, ALWAYS_UNAUTHENTICATED);

        var httpResponse = resource.forParticipant("alice", null);

        assertThat(httpResponse.getStatus()).isEqualTo(403);
    }
}
