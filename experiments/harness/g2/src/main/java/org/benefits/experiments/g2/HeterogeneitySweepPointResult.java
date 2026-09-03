package org.benefits.experiments.g2;

public record HeterogeneitySweepPointResult(
        long seed,
        double epsilon,
        int population,
        int k,
        double h,
        double excludedFraction,
        double coverageMedianRelativeError,
        double coverageIqrRelativeError,
        double exclusionMedianRelativeError,
        double exclusionIqrRelativeError,
        double suppressionRefusalRate,
        int dataPoints,
        Double survivingCoverageMedianRelativeError,
        Double survivingCoverageIqrRelativeError,
        Double survivingExclusionMedianRelativeError,
        Double survivingExclusionIqrRelativeError,
        int survivingDataPoints
) {
}
