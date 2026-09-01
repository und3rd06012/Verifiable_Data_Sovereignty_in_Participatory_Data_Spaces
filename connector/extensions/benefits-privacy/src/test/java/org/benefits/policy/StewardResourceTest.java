package org.benefits.policy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StewardResourceTest {

    private static final StewardAuthenticator ALWAYS_STEWARD_S1 = header -> "s-1";
    private static final StewardAuthenticator ALWAYS_UNAUTHENTICATED = header -> null;

    private StewardAuthorizationStore authorizationStore;
    private ReleaseRequestStore requestStore;
    private StewardResource resource;

    @BeforeEach
    void setUp() {
        authorizationStore = new InMemoryStewardAuthorizationStore();
        requestStore = new InMemoryReleaseRequestStore();
        resource = new StewardResource(authorizationStore, requestStore, ALWAYS_STEWARD_S1, null);
        requestStore.save(new ReleaseRequest("rr-1", "service-improvement", "statistics",
                List.of("service.satisfaction"), "athens", Instant.parse("2026-09-01T00:00:00Z")));
    }

    @Test
    void queueJoinsRequestsWithTheirAuthorizationState() {
        authorizationStore.appendSignature("rr-1", "hash-1",
                new StewardSignature("s-1", "sig-s-1", Instant.now()));

        var response = resource.queue("Basic irrelevant");

        assertThat(response.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var entries = (List<StewardResource.QueueEntry>) response.getEntity();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).request().releaseRequestId()).isEqualTo("rr-1");
        assertThat(entries.get(0).authorization().signatures()).hasSize(1);
    }

    @Test
    void queueRejectsAnUnauthenticatedCaller() {
        var unauthenticated = new StewardResource(authorizationStore, requestStore, ALWAYS_UNAUTHENTICATED, null);
        var response = unauthenticated.queue("Basic irrelevant");
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void coSignAppendsUnderTheAuthenticatedStewardsIdentityNotAClientSuppliedOne() {
        resource.coSign("rr-1", "Basic irrelevant", new StewardResource.CoSignBody("hash-1", "sig-anything"));

        var authorization = authorizationStore.findByReleaseRequestId("rr-1");
        assertThat(authorization.signatures()).hasSize(1);
        assertThat(authorization.signatures().get(0).stewardId()).isEqualTo("s-1");
    }

    @Test
    void vetoThenAppealSucceeds() {
        resource.veto("rr-1", "Basic irrelevant", new StewardResource.VetoBody("hash-1", "field set too broad"));
        var appealResponse = resource.appeal("rr-1", "Basic irrelevant",
                new StewardResource.AppealBody("veto was mistaken", "s-1"));

        assertThat(appealResponse.getStatus()).isEqualTo(201);
        assertThat(authorizationStore.findByReleaseRequestId("rr-1").appeals()).hasSize(1);
    }

    @Test
    void appealAgainstANonexistentVetoReturnsConflict() {
        var response = resource.appeal("rr-1", "Basic irrelevant",
                new StewardResource.AppealBody("no such veto", "s-nobody"));
        assertThat(response.getStatus()).isEqualTo(409);
    }
}
