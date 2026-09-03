package org.benefits.experiments.g1;

/**
 * The terminal outcome of one simulated availability timeline (spec section 3.2), for
 * both arms. armATicksToQuorum is null when Arm A never reached quorum within
 * AvailabilityTimelineRunner.MAX_TICKS (STARVED); Arm A has no other outcome, since it
 * never reads vetoes. armBTicksToQuorum is null when Arm B did not reach quorum, which
 * is either because armBVetoed is true (a hypothetical veto landed first, permanently
 * blocking Arm B) or, if armBVetoed is false, because Arm B also starved.
 */
public record RunOutcome(Integer armATicksToQuorum, Integer armBTicksToQuorum, boolean armBVetoed) {
}
