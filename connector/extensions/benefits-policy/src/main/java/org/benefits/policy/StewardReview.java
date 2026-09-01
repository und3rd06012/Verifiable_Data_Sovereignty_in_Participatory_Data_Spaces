package org.benefits.policy;

import java.time.Instant;

public record StewardReview(String stewardId, int rubricScore, String notes, Instant reviewedAt) {
}
