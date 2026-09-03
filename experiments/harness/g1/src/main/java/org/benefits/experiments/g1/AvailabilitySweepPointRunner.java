package org.benefits.experiments.g1;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs RUNS_PER_POINT independent seeded repetitions of AvailabilityTimelineRunner for
 * one sweep point and pools the results into that point's statistics (spec section
 * 3.3-3.4).
 */
public class AvailabilitySweepPointRunner {

    private final long baseSeed;

    public AvailabilitySweepPointRunner(long baseSeed) {
        this.baseSeed = baseSeed;
    }

    public AvailabilitySweepPointResult run(int n, double quorumFraction, double pAct, double vetoShare,
                                             int pointIndex, int runsPerPoint) {
        var quorumCount = Math.max(1, (int) Math.round(n * quorumFraction));
        var runner = new AvailabilityTimelineRunner();

        List<Integer> armAReachedTicks = new ArrayList<>();
        List<Integer> armBReachedTicks = new ArrayList<>();
        var armAReached = 0;
        var armBReached = 0;
        var armBVetoed = 0;

        for (var runIndex = 0; runIndex < runsPerPoint; runIndex++) {
            var seed = mix64(baseSeed * 1_000_003L + pointIndex * 1_000L + runIndex);
            var outcome = runner.run(n, quorumCount, pAct, vetoShare, seed);

            if (outcome.armATicksToQuorum() != null) {
                armAReached++;
                armAReachedTicks.add(outcome.armATicksToQuorum());
            }
            if (outcome.armBTicksToQuorum() != null) {
                armBReached++;
                armBReachedTicks.add(outcome.armBTicksToQuorum());
            } else if (outcome.armBVetoed()) {
                armBVetoed++;
            }
        }

        Double armAMedian = toDoubleMedianOrNull(armAReachedTicks);
        Double armAIqr = toDoubleIqrOrNull(armAReachedTicks);
        Double armBMedian = toDoubleMedianOrNull(armBReachedTicks);
        Double armBIqr = toDoubleIqrOrNull(armBReachedTicks);

        var armAStarved = runsPerPoint - armAReached;
        var armBStarved = runsPerPoint - armBReached - armBVetoed;

        return new AvailabilitySweepPointResult(
                baseSeed, n, quorumFraction, quorumCount, pAct, vetoShare, runsPerPoint,
                armAReached / (double) runsPerPoint,
                armAStarved / (double) runsPerPoint,
                armAMedian, armAIqr,
                armBReached / (double) runsPerPoint,
                armBVetoed / (double) runsPerPoint,
                armBStarved / (double) runsPerPoint,
                armBMedian, armBIqr
        );
    }

    private static Double toDoubleMedianOrNull(List<Integer> ticks) {
        if (ticks.isEmpty()) {
            return null;
        }
        return Statistics.median(ticks.stream().map(Integer::doubleValue).toList());
    }

    private static Double toDoubleIqrOrNull(List<Integer> ticks) {
        if (ticks.isEmpty()) {
            return null;
        }
        return Statistics.iqr(ticks.stream().map(Integer::doubleValue).toList());
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        z = z ^ (z >>> 33);
        return z;
    }
}
