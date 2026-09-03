package org.benefits.experiments.g1;

import java.util.List;

/**
 * Median and interquartile range via linear interpolation between order statistics
 * (the same convention as NumPy's default and R's type 7). Duplicated verbatim from
 * experiments/harness/e4's own Statistics class (spec section 3.4): g1 has no other
 * reason to depend on e4, so this one small, dependency-free class is copied rather
 * than shared.
 */
public final class Statistics {

    private Statistics() {
    }

    public static double median(List<Double> values) {
        return percentile(values, 0.5);
    }

    public static double iqr(List<Double> values) {
        return percentile(values, 0.75) - percentile(values, 0.25);
    }

    public static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("cannot compute a percentile over zero values");
        }
        var sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (sorted.length == 1) {
            return sorted[0];
        }
        var rank = p * (sorted.length - 1);
        var lowerIndex = (int) Math.floor(rank);
        var upperIndex = (int) Math.ceil(rank);
        if (lowerIndex == upperIndex) {
            return sorted[lowerIndex];
        }
        var fraction = rank - lowerIndex;
        return sorted[lowerIndex] + fraction * (sorted[upperIndex] - sorted[lowerIndex]);
    }
}
