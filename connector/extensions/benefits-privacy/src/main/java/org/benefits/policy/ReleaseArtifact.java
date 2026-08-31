package org.benefits.policy;

/**
 * An anonymised aggregate, computed once and stored.
 * <p>
 * The bytes are served unchanged on every transfer. Recomputing per transfer would
 * let a requester re-run the same transfer repeatedly and average the results to
 * recover the true value, while the budget accountant charged epsilon only once.
 */
public record ReleaseArtifact(String releaseRequestId, byte[] bytes, int computedK, double epsilonCharged) {

    public ReleaseArtifact {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
