package org.benefits.experiments.e4;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the full sweep (spec section 5): the cross product of every swept value in the
 * given SweepConfig, one SweepPointRunner call per point, written as a single CSV.
 */
public class E4SweepDriver {

    public void run(long seed, SweepConfig config, String csvPath) throws IOException {
        var runner = new SweepPointRunner(seed);
        List<String> lines = new ArrayList<>();
        lines.add("seed,epsilon,population,k,skew,median_relative_error,iqr_relative_error,"
                + "suppression_refusal_rate,data_points");

        for (var epsilonIndex = 0; epsilonIndex < config.epsilonValues().size(); epsilonIndex++) {
            var epsilon = config.epsilonValues().get(epsilonIndex);
            for (var populationIndex = 0; populationIndex < config.populationValues().size(); populationIndex++) {
                var population = config.populationValues().get(populationIndex);
                for (var kIndex = 0; kIndex < config.kValues().size(); kIndex++) {
                    var k = config.kValues().get(kIndex);
                    for (var skewIndex = 0; skewIndex < config.skewValues().size(); skewIndex++) {
                        var skewed = config.skewValues().get(skewIndex);
                        var result = runner.run(epsilon, population, k, skewed,
                                epsilonIndex, populationIndex, kIndex, skewIndex);
                        lines.add(toCsvRow(result));
                    }
                }
            }
        }

        var outPath = Path.of(csvPath);
        Files.createDirectories(outPath.toAbsolutePath().getParent());
        Files.write(outPath, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String toCsvRow(SweepPointResult result) {
        return String.join(",",
                Long.toString(result.seed()),
                Double.toString(result.epsilon()),
                Integer.toString(result.population()),
                Integer.toString(result.k()),
                result.skewed() ? "skewed" : "uniform",
                Double.toString(result.medianRelativeError()),
                Double.toString(result.iqrRelativeError()),
                Double.toString(result.suppressionRefusalRate()),
                Integer.toString(result.dataPoints())
        );
    }
}
