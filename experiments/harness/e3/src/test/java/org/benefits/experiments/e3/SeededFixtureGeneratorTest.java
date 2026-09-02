package org.benefits.experiments.e3;

import org.benefits.audit.AuditEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeededFixtureGeneratorTest {

    @Test
    void sameSeedProducesIdenticalEntries() {
        var a = new SeededFixtureGenerator(42).entries(50);
        var b = new SeededFixtureGenerator(42).entries(50);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentSeedsProduceDifferentEntries() {
        var a = new SeededFixtureGenerator(42).entries(50);
        var b = new SeededFixtureGenerator(43).entries(50);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void producesTheRequestedCount() {
        var entries = new SeededFixtureGenerator(1).entries(17);
        assertThat(entries).hasSize(17);
    }

    @Test
    void entriesHaveSequentialSeqStartingAtZero() {
        var entries = new SeededFixtureGenerator(1).entries(5);
        for (var i = 0; i < entries.size(); i++) {
            assertThat(entries.get(i).seq()).isEqualTo(i);
        }
    }

    @Test
    void entriesCarryTheSuppliedEpochId() {
        var entries = new SeededFixtureGenerator(1).entriesForEpoch(5, "epoch-a");
        for (var entry : entries) {
            assertThat(entry.epoch()).isEqualTo("epoch-a");
        }
    }
}
