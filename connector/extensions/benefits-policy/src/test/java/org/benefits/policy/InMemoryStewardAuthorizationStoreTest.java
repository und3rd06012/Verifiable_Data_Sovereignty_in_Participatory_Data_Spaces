package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryStewardAuthorizationStoreTest {

    private final StewardAuthorizationStore store = new InMemoryStewardAuthorizationStore();

    @Test
    void returnsNullWhenAbsent() {
        assertThat(store.findByReleaseRequestId("missing")).isNull();
    }

    @Test
    void roundTripsAnAuthorization() {
        var auth = authorization("rr-1", "s-1", "s-2");
        store.save(auth);
        assertThat(store.findByReleaseRequestId("rr-1")).isEqualTo(auth);
    }

    @Test
    void countsDistinctStewardsNotSignatures() {
        var auth = authorization("rr-2", "s-1", "s-1", "s-2");
        assertThat(auth.distinctStewardCount()).isEqualTo(2);
    }

    @Test
    void countsZeroForNoSignatures() {
        assertThat(authorization("rr-3").distinctStewardCount()).isZero();
    }

    @Test
    void mutatingCallerListAfterConstructionDoesNotChangeDistinctStewardCount() {
        var mutableSignatures = new java.util.ArrayList<StewardSignature>();
        mutableSignatures.add(new StewardSignature("s-1", "sig-s-1", Instant.parse("2026-08-30T12:00:00Z")));
        var auth = new StewardAuthorization("rr-4", "hash-abc", mutableSignatures, List.of(), List.of(), List.of(),
                Instant.parse("2026-08-30T12:00:00Z"));

        mutableSignatures.add(new StewardSignature("s-2", "sig-s-2", Instant.parse("2026-08-30T12:00:00Z")));

        assertThat(auth.distinctStewardCount()).isEqualTo(1);
    }

    @Test
    void appendReviewCreatesAnAuthorizationWhenNoneExists() {
        var review = new StewardReview("s-1", 4, "looks fine", Instant.parse("2026-08-30T12:00:00Z"));
        var updated = store.appendReview("rr-5", "hash-abc", review);
        assertThat(updated.reviews()).containsExactly(review);
        assertThat(updated.signatures()).isEmpty();
        assertThat(store.findByReleaseRequestId("rr-5")).isEqualTo(updated);
    }

    @Test
    void appendSignaturePreservesExistingReviewsAndVetoes() {
        var review = new StewardReview("s-1", 4, "looks fine", Instant.parse("2026-08-30T12:00:00Z"));
        store.appendReview("rr-6", "hash-abc", review);
        var signature = new StewardSignature("s-1", "sig-s-1", Instant.parse("2026-08-30T12:01:00Z"));
        var updated = store.appendSignature("rr-6", "hash-abc", signature);
        assertThat(updated.reviews()).containsExactly(review);
        assertThat(updated.signatures()).containsExactly(signature);
    }

    @Test
    void appendVetoDoesNotCountTowardDistinctStewardCount() {
        var veto = new StewardVeto("s-9", "field set too broad", Instant.parse("2026-08-30T12:00:00Z"));
        var updated = store.appendVeto("rr-7", "hash-abc", veto);
        assertThat(updated.vetoes()).containsExactly(veto);
        assertThat(updated.distinctStewardCount()).isZero();
    }

    @Test
    void appendAppealFailsWhenNoAuthorizationExists() {
        var appeal = new StewardAppeal("p-1", "veto was mistaken", "s-9", Instant.parse("2026-08-30T13:00:00Z"));
        assertThatThrownBy(() -> store.appendAppeal("rr-missing", appeal))
                .isInstanceOf(org.eclipse.edc.spi.EdcException.class);
    }

    @Test
    void appendAppealFailsWhenTheNamedVetoDoesNotExist() {
        store.appendVeto("rr-8", "hash-abc", new StewardVeto("s-9", "reason", Instant.parse("2026-08-30T12:00:00Z")));
        var appeal = new StewardAppeal("p-1", "veto was mistaken", "s-not-the-vetoer", Instant.parse("2026-08-30T13:00:00Z"));
        assertThatThrownBy(() -> store.appendAppeal("rr-8", appeal))
                .isInstanceOf(org.eclipse.edc.spi.EdcException.class);
    }

    @Test
    void appendAppealSucceedsAgainstAnExistingVeto() {
        store.appendVeto("rr-9", "hash-abc", new StewardVeto("s-9", "reason", Instant.parse("2026-08-30T12:00:00Z")));
        var appeal = new StewardAppeal("p-1", "veto was mistaken", "s-9", Instant.parse("2026-08-30T13:00:00Z"));
        var updated = store.appendAppeal("rr-9", appeal);
        assertThat(updated.appeals()).containsExactly(appeal);
    }

    private StewardAuthorization authorization(String releaseRequestId, String... stewardIds) {
        var signatures = java.util.Arrays.stream(stewardIds)
                .map(id -> new StewardSignature(id, "sig-" + id, Instant.parse("2026-08-30T12:00:00Z")))
                .toList();
        return new StewardAuthorization(releaseRequestId, "hash-abc", List.copyOf(signatures), List.of(), List.of(), List.of(),
                Instant.parse("2026-08-30T12:00:00Z"));
    }
}
