package org.benefits.experiments.e4;

import java.util.List;

public record SweepConfig(
        List<Double> epsilonValues,
        List<Integer> populationValues,
        List<Integer> kValues,
        List<Boolean> skewValues
) {
}
