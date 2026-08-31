package org.benefits.policy;

import org.jetbrains.annotations.Nullable;

public interface StewardAuthorizationStore {

    @Nullable
    StewardAuthorization findByReleaseRequestId(String releaseRequestId);

    void save(StewardAuthorization authorization);
}
