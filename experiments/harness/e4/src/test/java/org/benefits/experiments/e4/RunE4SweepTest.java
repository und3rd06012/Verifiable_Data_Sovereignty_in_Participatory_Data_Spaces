package org.benefits.experiments.e4;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunE4SweepTest {

    @Test
    void writesOneRowPerSweepPointWithTheExpectedHeader(@TempDir Path tempDir) throws IOException {
        var csvPath = tempDir.resolve("utility.csv");
        var config = new SweepConfig(
                List.of(0.5, 1.0),
                List.of(30, 100),
                List.of(3, 5),
                List.of(false, true)
        );

        new E4SweepDriver().run(1, config, csvPath.toString());

        var lines = Files.readAllLines(csvPath);
        assertThat(lines.get(0)).isEqualTo(
                "seed,epsilon,population,k,skew,median_relative_error,iqr_relative_error,"
                        + "suppression_refusal_rate,surviving_median_relative_error,"
                        + "surviving_iqr_relative_error,surviving_data_points,data_points");
        // 2 epsilon x 2 population x 2 k x 2 skew = 16 points, plus header.
        assertThat(lines).hasSize(17);
    }

    @Test
    void skewColumnIsHumanReadableUniformOrSkewed(@TempDir Path tempDir) throws IOException {
        var csvPath = tempDir.resolve("utility.csv");
        var config = new SweepConfig(List.of(1.0), List.of(100), List.of(5), List.of(false, true));

        new E4SweepDriver().run(1, config, csvPath.toString());

        var content = Files.readString(csvPath);
        assertThat(content).contains(",uniform,");
        assertThat(content).contains(",skewed,");
    }

    @Test
    void isReproducibleForTheSameSeed(@TempDir Path tempDir) throws IOException {
        var csvA = tempDir.resolve("a.csv");
        var csvB = tempDir.resolve("b.csv");
        var config = new SweepConfig(List.of(0.5, 1.5), List.of(30, 200), List.of(3, 7), List.of(false, true));

        new E4SweepDriver().run(9, config, csvA.toString());
        new E4SweepDriver().run(9, config, csvB.toString());

        assertThat(Files.readString(csvA)).isEqualTo(Files.readString(csvB));
    }
}
