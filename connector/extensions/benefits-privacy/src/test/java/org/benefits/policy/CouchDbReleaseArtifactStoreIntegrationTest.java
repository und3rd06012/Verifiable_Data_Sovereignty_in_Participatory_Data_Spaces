package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CouchDbReleaseArtifactStoreIntegrationTest {

    private CouchDbReleaseArtifactStore store;

    @BeforeEach
    void setUp() {
        store = new CouchDbReleaseArtifactStore("http://localhost:5984", "benefits-artifacts-test",
                "admin", "benefits", new ObjectMapper());
    }

    @Test
    void returnsNullWhenAbsent() {
        assertThat(store.findByReleaseRequestId("missing-" + System.nanoTime())).isNull();
    }

    @Test
    void roundTripsBytesUnchangedAcrossInstances() {
        var id = "rr-artifact-" + System.nanoTime();
        var payload = "{\"groups\":[{\"group\":\"app\",\"count\":12}]}".getBytes(StandardCharsets.UTF_8);
        store.save(new ReleaseArtifact(id, payload, 6, 0.5));

        var reloaded = new CouchDbReleaseArtifactStore("http://localhost:5984", "benefits-artifacts-test",
                "admin", "benefits", new ObjectMapper());
        var found = reloaded.findByReleaseRequestId(id);
        assertThat(found).isNotNull();
        assertThat(found.bytes()).isEqualTo(payload);
        assertThat(found.computedK()).isEqualTo(6);
        assertThat(found.epsilonCharged()).isEqualTo(0.5);
    }
}
