package org.benefits.policy;

import java.time.Instant;

/**
 * Contests a specific veto, named by the vetoing steward's id. A release request can
 * carry more than one veto (from different stewards); an appeal names which one it
 * contests rather than appealing "the" veto.
 */
public record StewardAppeal(String appellantId, String reason, String vetoStewardId, Instant appealedAt) {
}
