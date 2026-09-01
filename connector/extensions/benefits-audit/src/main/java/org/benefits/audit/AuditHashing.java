package org.benefits.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Canonical byte encoding and domain-separated hashing shared by MerkleTree (Task 3)
 * and EpochSealer (Task 4). The escape rule is byte-identical to
 * KAnonymity.KEY_ESCAPE in benefits-privacy: replace \ with \\, then | with \|, so a
 * field value can never be crafted to collide across the separator boundary.
 */
public final class AuditHashing {

    public static final String GENESIS_PREV_EPOCH_HASH = sha256Hex(new byte[0]);

    private AuditHashing() {
    }

    public static String escape(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\|");
    }

    public static byte[] canonicalBytes(AuditEntry entry) {
        var joined = String.join("|",
                escape(Long.toString(entry.seq())),
                escape(entry.epoch() == null ? "" : entry.epoch()),
                escape(entry.timestamp()),
                escape(entry.eventType()),
                escape(entry.actor()),
                escape(entry.datasetRef()),
                escape(entry.statedPurpose()),
                escape(entry.plainLanguage()));
        return joined.getBytes(StandardCharsets.UTF_8);
    }

    public static String leafHash(AuditEntry entry) {
        return domainHash((byte) 0x00, canonicalBytes(entry));
    }

    public static String nodeHash(String left, String right) {
        var leftBytes = HexFormat.of().parseHex(left);
        var rightBytes = HexFormat.of().parseHex(right);
        var combined = new byte[leftBytes.length + rightBytes.length];
        System.arraycopy(leftBytes, 0, combined, 0, leftBytes.length);
        System.arraycopy(rightBytes, 0, combined, leftBytes.length, rightBytes.length);
        return domainHash((byte) 0x01, combined);
    }

    public static String prevEpochHash(String epochId, String merkleRoot, String sealedAt) {
        var joined = String.join("|", escape(epochId), escape(merkleRoot), escape(sealedAt));
        return sha256Hex(joined.getBytes(StandardCharsets.UTF_8));
    }

    private static String domainHash(byte domain, byte[] data) {
        var combined = new byte[data.length + 1];
        combined[0] = domain;
        System.arraycopy(data, 0, combined, 1, data.length);
        return sha256Hex(combined);
    }

    private static String sha256Hex(byte[] data) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
