package org.benefits.experiments.g2;

import org.benefits.experiments.e4.E4Queries;
import org.benefits.experiments.e4.Statistics;
import org.benefits.policy.GroupedCount;
import org.benefits.policy.InMemoryBudgetAccountant;
import org.benefits.policy.InMemoryContributionSource;
import org.benefits.policy.KAnonymity;
import org.benefits.policy.LaplaceNoiseSource;
import org.benefits.policy.PrivacyPipeline;
import org.benefits.policy.QuerySpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Runs one G2 sweep point (spec section 4.4): the dual ground truth (full population
 * vs. consenting subset) for each of the eight E4Queries.workload() queries, rebuilt
 * with the fixed purpose/recipientClass so PrivacyPipeline's real consent filter
 * (Task 8) is genuinely exercised, against one shared InMemoryBudgetAccountant.
 */
public class HeterogeneitySweepPointRunner {

    public static final double TOTAL_SITE_BUDGET = 2.0;

    private final long seed;

    public HeterogeneitySweepPointRunner(long seed) {
        this.seed = seed;
    }

    public HeterogeneitySweepPointResult run(double epsilon, int population, int k, double h, int pointIndex) {
        var contributions = new HeterogeneousPopulationGenerator(seed).generate(population, h);
        var contributionSource = new InMemoryContributionSource(contributions);
        var noiseSeed = mix64(seed * 1_000_003L + pointIndex);
        var noise = new LaplaceNoiseSource(new Random(noiseSeed));
        var accountant = new InMemoryBudgetAccountant(TOTAL_SITE_BUDGET);
        var pipeline = new PrivacyPipeline(contributionSource, noise, accountant);

        List<Double> coverageErrors = new ArrayList<>();
        List<Double> exclusionErrors = new ArrayList<>();
        List<Double> survivingCoverageErrors = new ArrayList<>();
        List<Double> survivingExclusionErrors = new ArrayList<>();
        var suppressedOrRefused = 0;
        var totalDataPoints = 0;

        var excludedParticipants = new java.util.HashSet<String>();
        for (var query : E4Queries.workload()) {
            var scopedSpec = new QuerySpec(query.spec().siteId(), query.spec().groupBy(),
                    HeterogeneousPopulationGenerator.PURPOSE, HeterogeneousPopulationGenerator.RECIPIENT_CLASS);
            var consenting = contributions.stream()
                    .filter(c -> consentCovers(c, scopedSpec))
                    .toList();
            for (var c : contributions) {
                if (!consentCovers(c, scopedSpec)) {
                    excludedParticipants.add(c.participantRef());
                }
            }

            var fullTruth = KAnonymity.aggregate(contributions, query.spec(), 1);
            var consentingTruth = KAnonymity.aggregate(consenting, query.spec(), 1);
            var released = pipeline.compute("g2-" + query.name(), scopedSpec, k, epsilon);
            var observedByGroup = org.benefits.experiments.e4.ObservedCounts.parse(released);

            Map<String, Integer> fullByGroup = toMap(fullTruth);
            Map<String, Integer> consentingByGroup = toMap(consentingTruth);

            if (query.kind() == E4Queries.Kind.GROUPED) {
                for (var entry : fullByGroup.entrySet()) {
                    var fullCount = entry.getValue();
                    var consentingCount = consentingByGroup.getOrDefault(entry.getKey(), 0);
                    var observedCount = observedByGroup.get(entry.getKey());
                    totalDataPoints++;
                    if (released.isEmpty() || observedCount == null || consentingCount == 0) {
                        coverageErrors.add(1.0);
                        exclusionErrors.add(1.0);
                        suppressedOrRefused++;
                    } else {
                        var coverageError = Math.abs(observedCount - consentingCount) / (double) consentingCount;
                        var exclusionError = Math.abs(observedCount - fullCount) / (double) fullCount;
                        coverageErrors.add(coverageError);
                        exclusionErrors.add(exclusionError);
                        survivingCoverageErrors.add(coverageError);
                        survivingExclusionErrors.add(exclusionError);
                    }
                }
            } else {
                var targetCategory = query.kind() == E4Queries.Kind.PROPORTION_LARGEST
                        ? largestGroup(fullTruth)
                        : query.targetCategory();
                var fullTotal = fullTruth.stream().mapToInt(GroupedCount::count).sum();
                var consentingTotal = consentingTruth.stream().mapToInt(GroupedCount::count).sum();
                var fullTarget = fullByGroup.getOrDefault(targetCategory, 0);
                var consentingTarget = consentingByGroup.getOrDefault(targetCategory, 0);
                var fullProportion = fullTotal == 0 ? 0.0 : fullTarget / (double) fullTotal;
                var consentingProportion = consentingTotal == 0 ? 0.0 : consentingTarget / (double) consentingTotal;

                totalDataPoints++;
                // Deliberately not E4's own convention: E4 excluded a zero-true-
                // proportion data point from the pool entirely, since it only had one
                // population and a zero denominator was undefined. Here, a zero
                // consentingProportion with a non-zero fullProportion is itself a real,
                // interesting signal (heterogeneous consent made a whole target
                // category disappear from what the community can even ask about), so
                // it is sentinelled at 1.0 like a suppressed or refused query, not
                // dropped. This is astronomically rare at the swept population sizes
                // (well under one in a million for the legal-aid proportion query) but
                // must not crash or divide by zero if it occurs.
                if (consentingProportion == 0.0 || released.isEmpty()) {
                    coverageErrors.add(1.0);
                    exclusionErrors.add(1.0);
                    suppressedOrRefused++;
                } else {
                    var observedTotal = observedByGroup.values().stream().mapToDouble(Double::doubleValue).sum();
                    if (observedTotal <= 0.0) {
                        coverageErrors.add(1.0);
                        exclusionErrors.add(1.0);
                        suppressedOrRefused++;
                    } else {
                        var observedTarget = observedByGroup.getOrDefault(targetCategory, 0.0);
                        var observedProportion = observedTarget / observedTotal;
                        var coverageError = Math.abs(observedProportion - consentingProportion) / consentingProportion;
                        var exclusionError = fullProportion == 0.0 ? 1.0
                                : Math.abs(observedProportion - fullProportion) / fullProportion;
                        coverageErrors.add(coverageError);
                        exclusionErrors.add(exclusionError);
                        survivingCoverageErrors.add(coverageError);
                        survivingExclusionErrors.add(exclusionError);
                    }
                }
            }
        }

        var excludedFraction = excludedParticipants.size() / (double) contributions.size();

        Double survivingCoverageMedian = survivingCoverageErrors.isEmpty() ? null : Statistics.median(survivingCoverageErrors);
        Double survivingCoverageIqr = survivingCoverageErrors.isEmpty() ? null : Statistics.iqr(survivingCoverageErrors);
        Double survivingExclusionMedian = survivingExclusionErrors.isEmpty() ? null : Statistics.median(survivingExclusionErrors);
        Double survivingExclusionIqr = survivingExclusionErrors.isEmpty() ? null : Statistics.iqr(survivingExclusionErrors);

        return new HeterogeneitySweepPointResult(
                seed, epsilon, population, k, h,
                excludedFraction,
                Statistics.median(coverageErrors),
                Statistics.iqr(coverageErrors),
                Statistics.median(exclusionErrors),
                Statistics.iqr(exclusionErrors),
                suppressedOrRefused / (double) totalDataPoints,
                totalDataPoints,
                survivingCoverageMedian,
                survivingCoverageIqr,
                survivingExclusionMedian,
                survivingExclusionIqr,
                survivingCoverageErrors.size()
        );
    }

    /**
     * Deliberately omits PrivacyPipeline.consentCovers's three grantedAt/expiresAt/
     * revokedAt time-window conjuncts. This is safe only because every grant
     * HeterogeneousPopulationGenerator produces is unconditionally valid (a fixed
     * past grantedAt, expiresAt and revokedAt always null): if that generator is
     * ever changed to produce a grant with a real expiry or revocation, this method
     * must be updated to match PrivacyPipeline.consentCovers's full six-conjunct
     * rule, or the two would silently diverge.
     */
    private static boolean consentCovers(org.benefits.policy.Contribution c, QuerySpec query) {
        if (c.consentGrants() == null) {
            return true;
        }
        for (var field : query.groupBy()) {
            var covered = c.consentGrants().stream().anyMatch(grant ->
                    field.equals(grant.field())
                            && query.purpose().equals(grant.purpose())
                            && query.recipientClass().equals(grant.recipientClass()));
            if (!covered) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Integer> toMap(List<GroupedCount> groups) {
        Map<String, Integer> map = new HashMap<>();
        for (var g : groups) {
            map.put(g.groupKey(), g.count());
        }
        return map;
    }

    private static String largestGroup(List<GroupedCount> groundTruth) {
        return groundTruth.stream()
                .max((a, b) -> Integer.compare(a.count(), b.count()))
                .orElseThrow()
                .groupKey();
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        z = z ^ (z >>> 33);
        return z;
    }
}
