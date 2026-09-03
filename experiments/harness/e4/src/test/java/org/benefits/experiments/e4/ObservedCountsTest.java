package org.benefits.experiments.e4;

import org.benefits.policy.Contribution;
import org.benefits.policy.InMemoryBudgetAccountant;
import org.benefits.policy.InMemoryContributionSource;
import org.benefits.policy.LaplaceNoiseSource;
import org.benefits.policy.PrivacyPipeline;
import org.benefits.policy.QuerySpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservedCountsTest {

    @Test
    void emptyOptionalParsesToAnEmptyMap() {
        assertThat(ObservedCounts.parse(Optional.empty())).isEmpty();
    }

    @Test
    void parsesARealReleaseArtifactIntoGroupKeyToCountMap() {
        List<Contribution> contributions = new ArrayList<>();
        for (var i = 0; i < 8; i++) {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("serviceType", "housing");
            contributions.add(new Contribution("p-" + i, "obs-site", attrs));
        }
        var source = new InMemoryContributionSource(contributions);
        var noise = new LaplaceNoiseSource(new Random(1));
        var accountant = new InMemoryBudgetAccountant(2.0);
        var pipeline = new PrivacyPipeline(source, noise, accountant);

        var released = pipeline.compute("obs-1", new QuerySpec("obs-site", List.of("serviceType")), 5, 1.0);
        var counts = ObservedCounts.parse(released);

        assertThat(counts).containsKey("housing");
        assertThat(counts.get("housing")).isCloseTo(8.0, org.assertj.core.api.Assertions.within(20.0));
    }
}
