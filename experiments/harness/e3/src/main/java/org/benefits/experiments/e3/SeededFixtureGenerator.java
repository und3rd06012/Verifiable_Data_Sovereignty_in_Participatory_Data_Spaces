package org.benefits.experiments.e3;

import org.benefits.audit.AuditEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds synthetic AuditEntry lists deterministically from a seed, for E3's sweep.
 * java.util.Random(seed) is used rather than Math.random()/ThreadLocalRandom, both
 * of which are not seedable, so this generator is the only source of randomness the
 * sweep needs to reproduce for the "results reproduce from seed" gate.
 */
public class SeededFixtureGenerator {

    private final long seed;

    public SeededFixtureGenerator(long seed) {
        this.seed = seed;
    }

    public List<AuditEntry> entries(int count) {
        return entriesForEpoch(count, null);
    }

    public List<AuditEntry> entriesForEpoch(int count, String epochId) {
        var random = new Random(seed);
        List<AuditEntry> result = new ArrayList<>();
        for (var i = 0; i < count; i++) {
            result.add(new AuditEntry(
                    i,
                    epochId,
                    "2026-08-30T00:00:00.000Z",
                    "contribution.recorded",
                    "synthetic-actor-" + random.nextInt(1000),
                    "synthetic-asset-" + i,
                    "service-improvement",
                    "synthetic entry " + i + " seed " + seed
            ));
        }
        return result;
    }
}
