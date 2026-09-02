package org.benefits.experiments.e3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChainFixtureRunnerTest {

    @Test
    void writesOneChainEntryPerRequestedLength(@TempDir Path tempDir) throws IOException {
        var path = tempDir.resolve("chain-fixtures.json");
        new ChainFixtureRunner(3).run(List.of(5, 20), path.toString());

        var json = Files.readString(path);
        assertThat(json).contains("\"chainLength\":5");
        assertThat(json).contains("\"chainLength\":20");
    }

    @Test
    void eachChainStartsFromTheGenesisPrevEpochHash(@TempDir Path tempDir) throws IOException {
        var path = tempDir.resolve("chain-fixtures.json");
        new ChainFixtureRunner(3).run(List.of(5), path.toString());

        var json = Files.readString(path);
        assertThat(json).contains(org.benefits.audit.AuditHashing.GENESIS_PREV_EPOCH_HASH);
    }

    @Test
    void isReproducibleForTheSameSeed(@TempDir Path tempDir) throws IOException {
        var pathA = tempDir.resolve("a.json");
        var pathB = tempDir.resolve("b.json");
        new ChainFixtureRunner(3).run(List.of(5, 20), pathA.toString());
        new ChainFixtureRunner(3).run(List.of(5, 20), pathB.toString());
        assertThat(Files.readString(pathA)).isEqualTo(Files.readString(pathB));
    }
}
