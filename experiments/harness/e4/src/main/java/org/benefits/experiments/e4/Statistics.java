package org.benefits.experiments.e4;

import java.util.List;

/**
 * Median and interquartile range via linear interpolation between order statistics
 * (the same convention as NumPy's default and R's type 7), applied to the pooled
 * relative-error values one sweep point produces (spec section 7).
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
