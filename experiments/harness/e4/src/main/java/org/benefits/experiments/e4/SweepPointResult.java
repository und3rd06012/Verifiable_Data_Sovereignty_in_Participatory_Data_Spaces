package org.benefits.experiments.e4;

public record SweepPointResult(
        long seed,
        double epsilon,
        int population,
        int k,
        boolean skewed,
        double medianRelativeError,
        double iqrRelativeError,
        double suppressionRefusalRate,
        int dataPoints
) {
}
