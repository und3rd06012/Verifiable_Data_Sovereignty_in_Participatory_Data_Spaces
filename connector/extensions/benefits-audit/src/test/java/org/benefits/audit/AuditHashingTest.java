package org.benefits.audit;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class AuditHashingTest {

    private static final AuditEntry ENTRY = new AuditEntry(
            1L, "epoch-1", "2026-08-31T00:00:00Z", "negotiation-finalized",
            "participant-a", "asset-1", "service-improvement", "release approved");

    @Test
    void escapeOrdersBackslashBeforePipe() {
        assertThat(AuditHashing.escape("a\\b|c")).isEqualTo("a\\\\b\\|c");
    }

    @Test
    void canonicalBytesDoNotCollideAcrossTheFieldBoundary() throws Exception {
        var a = new AuditEntry(1L, "e", "t", "type", "actor|forged", "asset", "purpose", "plain");
        var b = new AuditEntry(1L, "e", "t", "type", "actor", "forged|asset", "purpose", "plain");
        assertThat(AuditHashing.canonicalBytes(a)).isNotEqualTo(AuditHashing.canonicalBytes(b));
    }

    @Test
    void leafHashIsDeterministic() {
        assertThat(AuditHashing.leafHash(ENTRY)).isEqualTo(AuditHashing.leafHash(ENTRY));
    }

    @Test
    void leafHashChangesWithAnyField() {
        var mutated = new AuditEntry(ENTRY.seq(), ENTRY.epoch(), ENTRY.timestamp(), ENTRY.eventType(),
                ENTRY.actor(), ENTRY.datasetRef(), ENTRY.statedPurpose(), "a different sentence");
        assertThat(AuditHashing.leafHash(ENTRY)).isNotEqualTo(AuditHashing.leafHash(mutated));
    }

    @Test
    void leafHashIsDomainSeparatedFromNodeHash() {
        var leaf = AuditHashing.leafHash(ENTRY);
        var node = AuditHashing.nodeHash(leaf, leaf);
        assertThat(leaf).isNotEqualTo(node);
    }

    @Test
    void nodeHashIsOrderSensitive() {
        var left = AuditHashing.leafHash(ENTRY);
        var right = AuditHashing.nodeHash(left, "0000000000000000000000000000000000000000000000000000000000000000");
        var reversed = AuditHashing.nodeHash("0000000000000000000000000000000000000000000000000000000000000000", left);
        assertThat(right).isNotEqualTo(reversed);
    }

    @Test
    void genesisPrevEpochHashIsSha256OfEmptyString() throws Exception {
        var expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("".getBytes(StandardCharsets.UTF_8)));
        assertThat(AuditHashing.GENESIS_PREV_EPOCH_HASH).isEqualTo(expected);
    }

    @Test
    void prevEpochHashIsDeterministicAndFieldSensitive() {
        var a = AuditHashing.prevEpochHash("epoch-1", "root-a", "2026-08-31T00:00:00Z");
        var b = AuditHashing.prevEpochHash("epoch-1", "root-b", "2026-08-31T00:00:00Z");
        assertThat(a).isNotEqualTo(b);
        assertThat(a).isEqualTo(AuditHashing.prevEpochHash("epoch-1", "root-a", "2026-08-31T00:00:00Z"));
    }
}
