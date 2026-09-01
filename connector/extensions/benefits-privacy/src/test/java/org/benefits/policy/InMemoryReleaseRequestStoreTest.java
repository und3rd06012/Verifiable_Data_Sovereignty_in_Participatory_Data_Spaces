package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryReleaseRequestStoreTest {

    private final ReleaseRequestStore store = new InMemoryReleaseRequestStore();

    @Test
    void returnsNullWhenAbsent() {
        assertThat(store.findById("missing")).isNull();
    }

    @Test
    void roundTripsARequest() {
        var request = new ReleaseRequest("rr-1", "service-improvement", "statistics",
                List.of("service.satisfaction"), "athens", Instant.parse("2026-09-01T00:00:00Z"));
        store.save(request);
        assertThat(store.findById("rr-1")).isEqualTo(request);
    }

    @Test
    void allReturnsEveryStoredRequest() {
        store.save(new ReleaseRequest("rr-2", "service-improvement", "statistics",
                List.of("service.satisfaction"), "athens", Instant.parse("2026-09-01T00:00:00Z")));
        store.save(new ReleaseRequest("rr-3", "marketing", "provider",
                List.of("health.condition"), "thessaloniki", Instant.parse("2026-09-01T01:00:00Z")));
        assertThat(store.all()).extracting(ReleaseRequest::releaseRequestId).contains("rr-2", "rr-3");
    }
}
