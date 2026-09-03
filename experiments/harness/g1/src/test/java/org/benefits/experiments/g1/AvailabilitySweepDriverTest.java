package org.benefits.experiments.g1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilitySweepDriverTest {

    @Test
    void writesOneHeaderRowPlusOneRowPerSweepPoint(@TempDir Path tempDir) throws IOException {
        var csvPath = tempDir.resolve("availability.csv").toString();
        var config = new AvailabilitySweepConfig(
                List.of(5, 10),
                List.of(0.3, 0.7),
                List.of(0.5),
                List.of(0.0, 0.1)
        );

        new AvailabilitySweepDriver().run(1L, config, csvPath, 10);

        var lines = Files.readAllLines(Path.of(csvPath));
        // 2 x 2 x 1 x 2 = 8 sweep points, plus 1 header row.
        assertThat(lines).hasSize(9);
        assertThat(lines.get(0)).isEqualTo("seed,N,quorum_fraction,quorum_count,p_act,veto_share,runs,"
                + "arm_a_reached_rate,arm_a_starved_rate,arm_a_median_ticks_to_quorum,"
                + "arm_a_iqr_ticks_to_quorum,arm_b_reached_rate,arm_b_vetoed_rate,arm_b_starved_rate,"
                + "arm_b_median_ticks_to_quorum,arm_b_iqr_ticks_to_quorum");
    }

    @Test
    void aStarvedPointsMedianColumnsAreWrittenAsEmptyStrings(@TempDir Path tempDir) throws IOException {
        var csvPath = tempDir.resolve("availability.csv").toString();
        var config = new AvailabilitySweepConfig(List.of(5), List.of(0.5), List.of(0.0), List.of(0.0));

        new AvailabilitySweepDriver().run(1L, config, csvPath, 10);

        var lines = Files.readAllLines(Path.of(csvPath));
        var dataRow = lines.get(1).split(",", -1);
        // p_act = 0.0: nobody ever acts, both arms starve every run in this one point.
        assertThat(dataRow[7]).isEqualTo("0.0");   // arm_a_reached_rate
        assertThat(dataRow[9]).isEmpty();          // arm_a_median_ticks_to_quorum
        assertThat(dataRow[10]).isEmpty();         // arm_a_iqr_ticks_to_quorum
    }
}
