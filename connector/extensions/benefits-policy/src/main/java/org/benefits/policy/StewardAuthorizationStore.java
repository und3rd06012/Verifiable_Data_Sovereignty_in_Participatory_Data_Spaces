package org.benefits.policy;

import org.jetbrains.annotations.Nullable;

public interface StewardAuthorizationStore {

    @Nullable
    StewardAuthorization findByReleaseRequestId(String releaseRequestId);

    /**
     * Stores the given authorization, replacing any existing authorization for the
     * same release request id wholesale rather than merging signatures into it. A
     * second save for the same release request id discards the previously stored
     * signatures entirely; it does not accumulate them.
     * <p>
     * Because of this replace semantics, the caller must be the sole writer for a
     * given release request id. Accumulating signatures from several stewards over
     * time, such as a portal where stewards sign independently and concurrently,
     * requires an atomic append operation that does not exist yet. Such an
     * operation must be added before any concurrent write path is introduced.
     */
    void save(StewardAuthorization authorization);
}
