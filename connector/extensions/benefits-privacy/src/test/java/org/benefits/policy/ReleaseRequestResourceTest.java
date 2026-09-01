package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseRequestResourceTest {

    private List<Contribution> seedContributions(String siteId, int count) {
        var contributions = new ArrayList<Contribution>();
        for (var i = 0; i < count; i++) {
            contributions.add(new Contribution("p-" + i, siteId, Map.of("field.channel", "app")));
        }
        return contributions;
    }

    private ReleaseRequestResource resourceWith(List<Contribution> contributions, ReleaseRequestStore requestStore,
                                                 ReleaseArtifactStore artifactStore, BudgetAccountant budgetAccountant) {
        return new ReleaseRequestResource(new InMemoryContributionSource(contributions), requestStore, artifactStore,
                budgetAccountant, new LaplaceNoiseSource(new SecureRandom()));
    }

    @Test
    void createsAReleaseRequestAndPersistsTheArtifact() {
        var contributions = seedContributions("athens", 8);
        var requestStore = new InMemoryReleaseRequestStore();
        var artifactStore = new InMemoryReleaseArtifactStore();
        var budgetAccountant = new InMemoryBudgetAccountant(1.0);
        var resource = resourceWith(contributions, requestStore, artifactStore, budgetAccountant);

        var body = new ReleaseRequestResource.CreateReleaseRequest(
                "rr-test-1", "service-improvement", "statistics", List.of("channel"), "athens", 6, 0.5);

        var response = resource.create(body);

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(requestStore.findById("rr-test-1")).isNotNull();
        assertThat(artifactStore.findByReleaseRequestId("rr-test-1")).isNotNull();
    }

    @Test
    void returnsConflictAndChargesNothingWhenBudgetIsInsufficient() {
        var contributions = seedContributions("athens", 8);
        var requestStore = new InMemoryReleaseRequestStore();
        var artifactStore = new InMemoryReleaseArtifactStore();
        var budgetAccountant = new InMemoryBudgetAccountant(1.0);
        budgetAccountant.reserve("athens", 0.9);
        var resource = resourceWith(contributions, requestStore, artifactStore, budgetAccountant);

        var body = new ReleaseRequestResource.CreateReleaseRequest(
                "rr-test-2", "service-improvement", "statistics", List.of("channel"), "athens", 6, 0.5);

        var response = resource.create(body);

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(requestStore.findById("rr-test-2")).isNull();
        assertThat(artifactStore.findByReleaseRequestId("rr-test-2")).isNull();
    }

    @Test
    void rejectsAMissingReleaseRequestId() {
        var requestStore = new InMemoryReleaseRequestStore();
        var artifactStore = new InMemoryReleaseArtifactStore();
        var budgetAccountant = new InMemoryBudgetAccountant(1.0);
        var resource = resourceWith(List.of(), requestStore, artifactStore, budgetAccountant);

        var body = new ReleaseRequestResource.CreateReleaseRequest(
                "", "service-improvement", "statistics", List.of("channel"), "athens", 6, 0.5);

        var response = resource.create(body);

        assertThat(response.getStatus()).isEqualTo(400);
    }
}
