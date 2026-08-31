package org.benefits.policy;

import org.jetbrains.annotations.Nullable;

/**
 * Holds computed anonymised artifacts, keyed by release request id.
 * <p>
 * Like the steward authorization store, save REPLACES any existing artifact for the
 * same release request id, and the caller must be the sole writer for a given id.
 * Unlike the budget accountant, that is acceptable here: replacing an artifact loses
 * data rather than violating a privacy guarantee, because the budget was already
 * charged when the artifact was computed.
 */
public interface ReleaseArtifactStore {

    @Nullable
    ReleaseArtifact findByReleaseRequestId(String releaseRequestId);

    void save(ReleaseArtifact artifact);
}
