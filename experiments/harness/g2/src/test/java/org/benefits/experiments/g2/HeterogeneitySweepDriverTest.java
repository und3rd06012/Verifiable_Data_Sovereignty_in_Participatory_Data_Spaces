package org.benefits.experiments.g2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeterogeneitySweepDriverTest {

    @Test
    void writesOneHeaderRowPlusOneRowPerSweepPoint(@TempDir Path tempDir) throws IOException {
        var csvPath = tempDir.resolve("heterogeneity.csv").toString();
        var config = new HeterogeneitySweepConfig(
                List.of(0.5, 1.0),
                List.of(100),
                5,
                List.of(0.0, 0.5, 1.0)
        );

        new HeterogeneitySweepDriver().run(1L, config, csvPath);

        var lines = Files.readAllLines(Path.of(csvPath));
        // 2 epsilons x 1 population x 3 h values = 6 points, plus 1 header row.
        assertThat(lines).hasSize(7);
        assertThat(lines.get(0)).isEqualTo("seed,epsilon,population,k,h,excluded_fraction,"
                + "coverage_median_relative_error,coverage_iqr_relative_error,"
                + "exclusion_median_relative_error,exclusion_iqr_relative_error,"
                + "suppression_refusal_rate,data_points");
    }
}
