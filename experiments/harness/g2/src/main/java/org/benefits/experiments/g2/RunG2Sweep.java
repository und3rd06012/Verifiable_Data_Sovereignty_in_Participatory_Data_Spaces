package org.benefits.experiments.g2;

import java.io.IOException;
import java.util.List;

public class RunG2Sweep {

    public static void main(String[] args) throws IOException {
        var seed = 1L;
        var config = new HeterogeneitySweepConfig(
                List.of(0.25, 0.5, 1.0, 2.0),
                List.of(100, 300),
                5,
                List.of(0.0, 0.1, 0.2, 0.3, 0.5, 0.7, 1.0)
        );

        new HeterogeneitySweepDriver().run(seed, config, "../../results/g2/heterogeneity.csv");

        System.out.println("G2 sweep complete");
    }
}
