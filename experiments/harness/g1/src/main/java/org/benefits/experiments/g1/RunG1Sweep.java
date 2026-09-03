package org.benefits.experiments.g1;

import java.io.IOException;
import java.util.List;

public class RunG1Sweep {

    public static final int RUNS_PER_POINT = 200;

    public static void main(String[] args) throws IOException {
        var seed = 1L;
        var config = new AvailabilitySweepConfig(
                List.of(5, 10, 20),
                List.of(0.3, 0.5, 0.7),
                List.of(0.1, 0.3, 0.5, 0.7, 0.9),
                List.of(0.0, 0.1, 0.3)
        );

        new AvailabilitySweepDriver().run(seed, config, "../../results/g1/availability.csv", RUNS_PER_POINT);

        System.out.println("G1 sweep complete");
    }
}
