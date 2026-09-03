package org.benefits.experiments.g1;

import java.util.List;

public record AvailabilitySweepConfig(
        List<Integer> nValues,
        List<Double> quorumFractionValues,
        List<Double> pActValues,
        List<Double> vetoShareValues
) {
}
