package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CouchDbReleaseRequestStoreIntegrationTest {

    private CouchDbReleaseRequestStore store;

    @BeforeEach
    void setUp() {
        store = new CouchDbReleaseRequestStore("http://localhost:5984", "benefits-requests-test",
                "admin", "benefits", new ObjectMapper());
    }

    @Test
    void roundTripsAcrossInstances() {
        var id = "rr-persist-" + System.nanoTime();
        var request = new ReleaseRequest(id, "service-improvement", "statistics",
                List.of("service.satisfaction"), "athens", Instant.parse("2026-09-01T00:00:00Z"));
        store.save(request);

        var reloaded = new CouchDbReleaseRequestStore("http://localhost:5984", "benefits-requests-test",
                "admin", "benefits", new ObjectMapper());
        assertThat(reloaded.findById(id)).isEqualTo(request);
    }

    @Test
    void allIncludesEveryPersistedRequest() {
        var id = "rr-all-" + System.nanoTime();
        store.save(new ReleaseRequest(id, "service-improvement", "statistics",
                List.of("service.satisfaction"), "athens", Instant.parse("2026-09-01T00:00:00Z")));
        assertThat(store.all()).extracting(ReleaseRequest::releaseRequestId).contains(id);
    }
}
