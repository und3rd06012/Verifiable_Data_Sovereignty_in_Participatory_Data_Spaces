package org.benefits.experiments.e3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProofSizeRunnerTest {

    @Test
    void writesOneCsvRowPerSampledLeafPerEpochSize(@TempDir Path tempDir) throws IOException {
        var csvPath = tempDir.resolve("proof-size.csv");
        var fixturesPath = tempDir.resolve("inclusion-fixtures.json");

        new ProofSizeRunner(7).run(List.of(5, 20), csvPath.toString(), fixturesPath.toString());

        var lines = Files.readAllLines(csvPath);
        assertThat(lines.get(0)).isEqualTo("seed,epoch_size,leaf_index,proof_steps,proof_bytes");
        // epoch size 5 (< 10): every leaf sampled, 5 rows. epoch size 20: 10 sampled, 10 rows.
        assertThat(lines).hasSize(1 + 5 + 10);
    }

    @Test
    void proofBytesIsThirtyTwoTimesProofSteps(@TempDir Path tempDir) throws IOException {
        var csvPath = tempDir.resolve("proof-size.csv");
        var fixturesPath = tempDir.resolve("inclusion-fixtures.json");

        new ProofSizeRunner(7).run(List.of(20), csvPath.toString(), fixturesPath.toString());

        var lines = Files.readAllLines(csvPath);
        for (var i = 1; i < lines.size(); i++) {
            var fields = lines.get(i).split(",");
            var steps = Integer.parseInt(fields[3]);
            var bytes = Integer.parseInt(fields[4]);
            assertThat(bytes).isEqualTo(steps * 32);
        }
    }

    @Test
    void writesAFixtureFileWithVerifiableEntriesForEverySampledPoint(@TempDir Path tempDir) throws IOException {
        var csvPath = tempDir.resolve("proof-size.csv");
        var fixturesPath = tempDir.resolve("inclusion-fixtures.json");

        new ProofSizeRunner(7).run(List.of(5), csvPath.toString(), fixturesPath.toString());

        var json = Files.readString(fixturesPath);
        assertThat(json).contains("\"epochSize\":5");
        assertThat(json).contains("\"leafIndex\"");
        assertThat(json).contains("\"leaf\"");
        assertThat(json).contains("\"root\"");
        assertThat(json).contains("\"steps\"");
        assertThat(json).contains("\"siblingHash\"");
        assertThat(json).contains("\"siblingIsRight\"");
    }

    @Test
    void isReproducibleForTheSameSeed(@TempDir Path tempDir) throws IOException {
        var csvA = tempDir.resolve("a.csv");
        var jsonA = tempDir.resolve("a.json");
        var csvB = tempDir.resolve("b.csv");
        var jsonB = tempDir.resolve("b.json");

        new ProofSizeRunner(99).run(List.of(10, 50), csvA.toString(), jsonA.toString());
        new ProofSizeRunner(99).run(List.of(10, 50), csvB.toString(), jsonB.toString());

        assertThat(Files.readString(csvA)).isEqualTo(Files.readString(csvB));
        assertThat(Files.readString(jsonA)).isEqualTo(Files.readString(jsonB));
    }
}
