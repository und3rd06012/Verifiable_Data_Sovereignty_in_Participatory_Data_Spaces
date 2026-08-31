package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

    private StewardAuthorization authorization(String releaseRequestId, String... stewardIds) {
        var signatures = java.util.Arrays.stream(stewardIds)
                .map(id -> new StewardSignature(id, "sig-" + id, Instant.parse("2026-08-30T12:00:00Z")))
                .toList();
        return new StewardAuthorization(releaseRequestId, "hash-abc", List.copyOf(signatures),
                Instant.parse("2026-08-30T12:00:00Z"));
    }
}
