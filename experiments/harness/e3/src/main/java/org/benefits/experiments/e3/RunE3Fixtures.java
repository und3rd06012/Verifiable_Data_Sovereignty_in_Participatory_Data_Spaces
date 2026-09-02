package org.benefits.experiments.e3;

import java.io.IOException;
import java.util.List;

public class RunE3Fixtures {

    public static void main(String[] args) throws IOException {
        var seed = 1L;
        var epochSizes = List.of(10, 50, 100, 500, 1000, 5000);
        var chainLengths = List.of(5, 20, 100, 500);

        new ProofSizeRunner(seed).run(
                epochSizes,
                "../../results/e3/proof-size.csv",
                "fixtures/inclusion-fixtures.json"
        );
        new ChainFixtureRunner(seed).run(
                chainLengths,
                "fixtures/chain-fixtures.json"
        );

        System.out.println("E3 fixture generation complete");
    }
}
