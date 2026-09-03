package org.benefits.experiments.g1;

public record AvailabilitySweepPointResult(
        long seed,
        int n,
        double quorumFraction,
        int quorumCount,
        double pAct,
        double vetoShare,
        int runs,
        double armAReachedRate,
        double armAStarvedRate,
        Double armAMedianTicksToQuorum,
        Double armAIqrTicksToQuorum,
        double armBReachedRate,
        double armBVetoedRate,
        double armBStarvedRate,
        Double armBMedianTicksToQuorum,
        Double armBIqrTicksToQuorum
) {
}
