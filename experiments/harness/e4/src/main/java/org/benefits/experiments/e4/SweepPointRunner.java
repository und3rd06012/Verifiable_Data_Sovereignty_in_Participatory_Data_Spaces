package org.benefits.experiments.e4;

import org.benefits.policy.GroupedCount;
import org.benefits.policy.InMemoryBudgetAccountant;
import org.benefits.policy.InMemoryContributionSource;
import org.benefits.policy.KAnonymity;
import org.benefits.policy.LaplaceNoiseSource;
import org.benefits.policy.PrivacyPipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Runs one sweep point: generates the seeded population, runs the eight-query workload
 * (E4Queries.workload()) in order against one shared InMemoryBudgetAccountant (spec
 * section 6, total budget fixed at TOTAL_SITE_BUDGET), and pools every relative-error
 * data point the workload produces into that point's utility statistics (spec section 7).
 */
public class SweepPointRunner {

    public static final double TOTAL_SITE_BUDGET = 2.0;

    private final long seed;

    public SweepPointRunner(long seed) {
        this.seed = seed;
    }

    public SweepPointResult run(double epsilon, int population, int k, boolean skewed,
                                 int epsilonIndex, int populationIndex, int kIndex, int skewIndex) {
        var contributions = new SeededPopulationGenerator(seed).generate(population, skewed);
        var contributionSource = new InMemoryContributionSource(contributions);
        var noiseSeed = pointSeed(epsilonIndex, populationIndex, kIndex, skewIndex);
        var noise = new LaplaceNoiseSource(new Random(noiseSeed));
        var accountant = new InMemoryBudgetAccountant(TOTAL_SITE_BUDGET);
        var pipeline = new PrivacyPipeline(contributionSource, noise, accountant);

        List<Double> relativeErrors = new ArrayList<>();
        var suppressedOrRefused = 0;

        for (var query : E4Queries.workload()) {
            var groundTruth = KAnonymity.aggregate(contributions, query.spec(), 1);
            Map<String, Integer> trueByGroup = new HashMap<>();
            for (var g : groundTruth) {
                trueByGroup.put(g.groupKey(), g.count());
            }

            var released = pipeline.compute("e4-" + query.name(), query.spec(), k, epsilon);
            var observedByGroup = ObservedCounts.parse(released);

            if (query.kind() == E4Queries.Kind.GROUPED) {
                for (var entry : trueByGroup.entrySet()) {
                    var trueCount = entry.getValue();
                    var observedCount = observedByGroup.get(entry.getKey());
                    if (released.isEmpty() || observedCount == null) {
                        relativeErrors.add(1.0);
                        suppressedOrRefused++;
                    } else {
                        relativeErrors.add(Math.abs(observedCount - trueCount) / (double) trueCount);
                    }
                }
            } else {
                var targetCategory = query.kind() == E4Queries.Kind.PROPORTION_LARGEST
                        ? largestGroup(groundTruth)
                        : query.targetCategory();
                var trueTotal = groundTruth.stream().mapToInt(GroupedCount::count).sum();
                var trueTarget = trueByGroup.getOrDefault(targetCategory, 0);
                var trueProportion = trueTarget / (double) trueTotal;

                if (released.isEmpty()) {
                    relativeErrors.add(1.0);
                    suppressedOrRefused++;
                } else {
                    var observedTotal = observedByGroup.values().stream().mapToDouble(Double::doubleValue).sum();
                    if (observedTotal == 0.0) {
                        relativeErrors.add(1.0);
                        suppressedOrRefused++;
                    } else {
                        var observedTarget = observedByGroup.getOrDefault(targetCategory, 0.0);
                        var observedProportion = observedTarget / observedTotal;
                        relativeErrors.add(Math.abs(observedProportion - trueProportion) / trueProportion);
                    }
                }
            }
        }

        return new SweepPointResult(
                seed, epsilon, population, k, skewed,
                Statistics.median(relativeErrors),
                Statistics.iqr(relativeErrors),
                suppressedOrRefused / (double) relativeErrors.size(),
                relativeErrors.size()
        );
    }

    private static String largestGroup(List<GroupedCount> groundTruth) {
        return groundTruth.stream()
                .max((a, b) -> Integer.compare(a.count(), b.count()))
                .orElseThrow()
                .groupKey();
    }

    private long pointSeed(int epsilonIndex, int populationIndex, int kIndex, int skewIndex) {
        var combined = epsilonIndex;
        combined = combined * 5 + populationIndex;
        combined = combined * 4 + kIndex;
        combined = combined * 2 + skewIndex;
        return seed * 1_000_003L + combined;
    }
}
