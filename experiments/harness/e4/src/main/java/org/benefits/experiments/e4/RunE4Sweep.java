package org.benefits.experiments.e4;

import java.io.IOException;
import java.util.List;

public class RunE4Sweep {

    public static void main(String[] args) throws IOException {
        var seed = 1L;
        var config = new SweepConfig(
                List.of(0.1, 0.5, 1.0, 1.5, 2.0),
                List.of(30, 100, 200, 300),
                List.of(3, 5, 7, 10),
                List.of(false, true)
        );

        new E4SweepDriver().run(seed, config, "../../results/e4/utility.csv");

        System.out.println("E4 sweep complete");
    }
}
