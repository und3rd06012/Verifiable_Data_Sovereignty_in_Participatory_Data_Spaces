package org.benefits.policy;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Holds what was asked for, keyed by release request id, distinct from
 * StewardAuthorizationStore, which holds what stewards decided about it. A request is
 * created once (the POST /release-requests handler) and never mutated afterward, so
 * save() is a plain replace with no concurrent-writer scenario to guard against,
 * unlike StewardAuthorizationStore's append* methods.
 */
public interface ReleaseRequestStore {

    @Nullable
    ReleaseRequest findById(String releaseRequestId);

    void save(ReleaseRequest request);

    List<ReleaseRequest> all();
}
