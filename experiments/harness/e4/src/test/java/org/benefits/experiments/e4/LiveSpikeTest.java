package org.benefits.experiments.e4;

import org.benefits.policy.Contribution;
import org.benefits.policy.InMemoryBudgetAccountant;
import org.benefits.policy.InMemoryContributionSource;
import org.benefits.policy.KAnonymity;
import org.benefits.policy.LaplaceNoiseSource;
import org.benefits.policy.PrivacyPipeline;
import org.benefits.policy.QuerySpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Exercises the real, unmodified privacy pipeline classes directly with synthetic data,
 * confirming the behavior this plan's later tasks depend on, before building on it.
 */
class LiveSpikeTest {

    private static List<Contribution> contributionsWithGroupSizes(int belowK, int atOrAboveK) {
        List<Contribution> contributions = new ArrayList<>();
        for (var i = 0; i < belowK; i++) {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("serviceType", "legal-aid");
            contributions.add(new Contribution("p-small-" + i, "spike-site", attrs));
        }
        for (var i = 0; i < atOrAboveK; i++) {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("serviceType", "housing");
            contributions.add(new Contribution("p-large-" + i, "spike-site", attrs));
        }
        return contributions;
    }

    @Test
    void aGroupAtOrAboveKSurvivesAndIsNoised() {
        var contributions = contributionsWithGroupSizes(0, 10);
        var source = new InMemoryContributionSource(contributions);
        var noise = new LaplaceNoiseSource(new Random(1));
        var accountant = new InMemoryBudgetAccountant(2.0);
        var pipeline = new PrivacyPipeline(source, noise, accountant);

        var released = pipeline.compute("spike-1", new QuerySpec("spike-site", List.of("serviceType")), 5, 1.0);

        assertThat(released).isPresent();
        assertThat(released.get().computedK()).isEqualTo(10);
        var json = new String(released.get().bytes());
        assertThat(json).contains("housing");
    }

    @Test
    void aGroupBelowKIsSuppressedEntirely() {
        var contributions = contributionsWithGroupSizes(3, 0);
        var source = new InMemoryContributionSource(contributions);
        var noise = new LaplaceNoiseSource(new Random(1));
        var accountant = new InMemoryBudgetAccountant(2.0);
        var pipeline = new PrivacyPipeline(source, noise, accountant);

        var released = pipeline.compute("spike-2", new QuerySpec("spike-site", List.of("serviceType")), 5, 1.0);

        assertThat(released).isPresent();
        var json = new String(released.get().bytes());
        assertThat(json).doesNotContain("legal-aid");
    }

    @Test
    void budgetExhaustionRefusesAQueryAndReservesNothing() {
        var contributions = contributionsWithGroupSizes(0, 10);
        var source = new InMemoryContributionSource(contributions);
        var noise = new LaplaceNoiseSource(new Random(1));
        var accountant = new InMemoryBudgetAccountant(1.0);
        var pipeline = new PrivacyPipeline(source, noise, accountant);
        var query = new QuerySpec("spike-site", List.of("serviceType"));

        var first = pipeline.compute("spike-3a", query, 5, 0.8);
        assertThat(first).isPresent();
        // 1.0 - 0.8 is not exactly representable in IEEE-754 double; the real
        // InMemoryBudgetAccountant does raw double subtraction with no rounding, so this
        // must be a tolerance comparison, not exact equality. See task-2-report.md.
        var afterFirst = accountant.remaining("spike-site");
        assertThat(afterFirst).isCloseTo(0.2, within(1e-12));

        var second = pipeline.compute("spike-3b", query, 5, 0.8);
        assertThat(second).isEmpty();
        // Exact equality here is deliberate: bit-for-bit unchanged is the direct proof
        // that a refused reservation charges nothing at all.
        assertThat(accountant.remaining("spike-site")).isEqualTo(afterFirst);
    }

    @Test
    void kAnonymityAggregateAtKOneNeverSuppressesAndGivesTrueCounts() {
        var contributions = contributionsWithGroupSizes(2, 10);
        var groups = KAnonymity.aggregate(contributions, new QuerySpec("spike-site", List.of("serviceType")), 1);

        var byKey = new HashMap<String, Integer>();
        for (var g : groups) {
            byKey.put(g.groupKey(), g.count());
        }
        assertThat(byKey).containsEntry("legal-aid", 2);
        assertThat(byKey).containsEntry("housing", 10);
    }

    @Test
    void releasedJsonShapeIsParseableGroupsArrayOfGroupAndCount() throws Exception {
        var contributions = contributionsWithGroupSizes(0, 6);
        var source = new InMemoryContributionSource(contributions);
        var noise = new LaplaceNoiseSource(new Random(1));
        var accountant = new InMemoryBudgetAccountant(2.0);
        var pipeline = new PrivacyPipeline(source, noise, accountant);

        var released = pipeline.compute("spike-4", new QuerySpec("spike-site", List.of("serviceType")), 5, 1.0);
        assertThat(released).isPresent();

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.readTree(released.get().bytes());
        assertThat(root.has("groups")).isTrue();
        assertThat(root.get("groups").isArray()).isTrue();
        var first = root.get("groups").get(0);
        assertThat(first.has("group")).isTrue();
        assertThat(first.has("count")).isTrue();
    }
}
