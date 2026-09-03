package org.benefits.policy;

/**
 * One participant's consent grant for a single (field, purpose, recipientClass)
 * combination, matching packages/consent-core/src/types.ts's ConsentGrant shape.
 * Timestamps are ISO-8601 strings compared lexically, the same convention
 * ConsentEvaluator.hasGrant already uses against raw JSON: grantedAt/expiresAt/
 * revokedAt sort correctly as plain strings without a java.time parse step.
 */
public record ConsentGrant(
        String field,
        String purpose,
        String recipientClass,
        String grantedAt,
        String expiresAt,
        String revokedAt
) {
}
