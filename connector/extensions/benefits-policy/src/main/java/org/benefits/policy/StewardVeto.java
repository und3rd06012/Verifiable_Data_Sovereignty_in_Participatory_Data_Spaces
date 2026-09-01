package org.benefits.policy;

import java.time.Instant;

public record StewardVeto(String stewardId, String reason, Instant vetoedAt) {
}
