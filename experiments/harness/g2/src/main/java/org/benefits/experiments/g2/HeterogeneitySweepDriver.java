package org.benefits.experiments.g2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the full G2 sweep (spec section 4.3): the cross product of every swept value in
 * the given HeterogeneitySweepConfig, one HeterogeneitySweepPointRunner call per point,
 * written as a single CSV.
 */
public class HeterogeneitySweepDriver {

    public void run(long seed, HeterogeneitySweepConfig config, String csvPath) throws IOException {
        var runner = new HeterogeneitySweepPointRunner(seed);
        List<String> lines = new ArrayList<>();
        lines.add("seed,epsilon,population,k,h,excluded_fraction,"
                + "coverage_median_relative_error,coverage_iqr_relative_error,"
                + "exclusion_median_relative_error,exclusion_iqr_relative_error,"
                + "suppression_refusal_rate,data_points");

        var pointIndex = 0;
        for (var epsilon : config.epsilonValues()) {
            for (var population : config.populationValues()) {
                for (var h : config.hValues()) {
                    var result = runner.run(epsilon, population, config.k(), h, pointIndex);
                    lines.add(toCsvRow(result));
                    pointIndex++;
                }
            }
        }

        var outPath = Path.of(csvPath);
        Files.createDirectories(outPath.toAbsolutePath().getParent());
        Files.write(outPath, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String toCsvRow(HeterogeneitySweepPointResult result) {
        return String.join(",",
                Long.toString(result.seed()),
                Double.toString(result.epsilon()),
                Integer.toString(result.population()),
                Integer.toString(result.k()),
                Double.toString(result.h()),
                Double.toString(result.excludedFraction()),
                Double.toString(result.coverageMedianRelativeError()),
                Double.toString(result.coverageIqrRelativeError()),
                Double.toString(result.exclusionMedianRelativeError()),
                Double.toString(result.exclusionIqrRelativeError()),
                Double.toString(result.suppressionRefusalRate()),
                Integer.toString(result.dataPoints())
        );
    }
}
