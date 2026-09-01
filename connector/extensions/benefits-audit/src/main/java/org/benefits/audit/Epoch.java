package org.benefits.audit;

public record Epoch(
        String epochId,
        String merkleRoot,
        String prevEpochHash,
        String sealedAt) {
}
