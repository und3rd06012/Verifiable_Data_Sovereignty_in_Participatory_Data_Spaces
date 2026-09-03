package org.benefits.experiments.g2;

import org.benefits.policy.Contribution;
import org.benefits.policy.ConsentGrant;
import org.benefits.policy.InMemoryBudgetAccountant;
import org.benefits.policy.InMemoryContributionSource;
import org.benefits.policy.KAnonymity;
import org.benefits.policy.LaplaceNoiseSource;
import org.benefits.policy.PrivacyPipeline;
import org.benefits.policy.QuerySpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real, now-consent-aware PrivacyPipeline.compute directly with a small
 * hand-built population, confirming the filter's end-to-end behavior before Task 11's
 * sweep runner is built on it.
 */
class LiveSpikeTest {

    @Test
    void withHeterogeneousConsentTheConsentAwareQueryExcludesUncoveredParticipantsButTheLegacyQueryDoesNot() {
        var covered = new Contribution("p-1", "g2-site", Map.of("serviceType", "housing"),
                List.of(new ConsentGrant("serviceType", "community-insight", "community",
                        "2020-01-01T00:00:00Z", null, null)));
        var uncovered = new Contribution("p-2", "g2-site", Map.of("serviceType", "housing"),
                List.of());
        var source = new InMemoryContributionSource(List.of(covered, uncovered));
        var pipeline = new PrivacyPipeline(source, new LaplaceNoiseSource(new Random(1)),
                new InMemoryBudgetAccountant(2.0));

        var legacyQuery = new QuerySpec("g2-site", List.of("serviceType"));
        var legacyReleased = pipeline.compute("spike-legacy", legacyQuery, 1, 1.0);
        assertThat(legacyReleased).isPresent();
        assertThat(legacyReleased.get().computedK()).isEqualTo(2);

        var consentAwareQuery = new QuerySpec("g2-site", List.of("serviceType"),
                HeterogeneousPopulationGenerator.PURPOSE, HeterogeneousPopulationGenerator.RECIPIENT_CLASS);
        var consentAwareReleased = pipeline.compute("spike-aware", consentAwareQuery, 1, 1.0);
        assertThat(consentAwareReleased).isPresent();
        assertThat(consentAwareReleased.get().computedK()).isEqualTo(1);
    }

    @Test
    void groundTruthOverTheFullPopulationDiffersFromGroundTruthOverTheConsentingSubset() {
        var covered = new Contribution("p-1", "g2-site", Map.of("serviceType", "housing"),
                List.of(new ConsentGrant("serviceType", "community-insight", "community",
                        "2020-01-01T00:00:00Z", null, null)));
        var uncovered = new Contribution("p-2", "g2-site", Map.of("serviceType", "housing"),
                List.of());
        var query = new QuerySpec("g2-site", List.of("serviceType"));

        var fullTruth = KAnonymity.aggregate(List.of(covered, uncovered), query, 1);
        var consentingTruth = KAnonymity.aggregate(List.of(covered), query, 1);

        assertThat(fullTruth.get(0).count()).isEqualTo(2);
        assertThat(consentingTruth.get(0).count()).isEqualTo(1);
    }
}
