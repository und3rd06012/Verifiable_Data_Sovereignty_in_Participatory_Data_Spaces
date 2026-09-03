package org.benefits.experiments.g1;

import org.benefits.policy.StewardAuthorization;

/**
 * NOT real production behavior. The real, unmodified StewardQuorumFunction (spec
 * section 3.1) never reads vetoes at all; this class exists only to show, on the same
 * simulated timeline as Arm A, how the availability/deadlock picture would differ if a
 * veto permanently blocked quorum, an intuitive but currently false reading of "veto"
 * this project's own steward-portal UI does not implement or claim.
 */
public final class HypotheticalVetoBlocksQuorumFunction {

    private HypotheticalVetoBlocksQuorumFunction() {
    }

    public static boolean evaluate(StewardAuthorization authorization, int required) {
        return authorization.vetoes().isEmpty() && authorization.distinctStewardCount() >= required;
    }
}
