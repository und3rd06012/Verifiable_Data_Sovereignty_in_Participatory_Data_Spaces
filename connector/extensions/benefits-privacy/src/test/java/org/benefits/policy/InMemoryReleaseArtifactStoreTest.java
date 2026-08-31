package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryReleaseArtifactStoreTest {

    private final ReleaseArtifactStore store = new InMemoryReleaseArtifactStore();

    private ReleaseArtifact artifact(String id) {
        return new ReleaseArtifact(id, "{}".getBytes(StandardCharsets.UTF_8), 6, 0.5);
    }

    @Test
    void returnsNullWhenAbsent() {
        assertThat(store.findByReleaseRequestId("missing")).isNull();
    }

    @Test
    void roundTripsAnArtifact() {
        store.save(artifact("rr-1"));
        var found = store.findByReleaseRequestId("rr-1");
        assertThat(found).isNotNull();
        assertThat(found.computedK()).isEqualTo(6);
        assertThat(found.epsilonCharged()).isEqualTo(0.5);
    }

    @Test
    void servesIdenticalBytesOnEveryRead() {
        store.save(artifact("rr-1"));
        assertThat(store.findByReleaseRequestId("rr-1").bytes())
                .isEqualTo(store.findByReleaseRequestId("rr-1").bytes());
    }

    @Test
    void mutatingReturnedBytesDoesNotChangeTheStoredArtifact() {
        store.save(artifact("rr-1"));
        var first = store.findByReleaseRequestId("rr-1").bytes();
        first[0] = (byte) 'X';
        assertThat(store.findByReleaseRequestId("rr-1").bytes()[0]).isEqualTo((byte) '{');
    }
}
