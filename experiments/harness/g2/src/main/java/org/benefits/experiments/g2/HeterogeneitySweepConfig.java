package org.benefits.experiments.g2;

import java.util.List;

public record HeterogeneitySweepConfig(
        List<Double> epsilonValues,
        List<Integer> populationValues,
        int k,
        List<Double> hValues
) {
}
