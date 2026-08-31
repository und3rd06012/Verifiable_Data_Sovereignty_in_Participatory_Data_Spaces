package org.benefits.policy;

import java.time.Instant;

public record StewardSignature(String stewardId, String signature, Instant signedAt) {
}
