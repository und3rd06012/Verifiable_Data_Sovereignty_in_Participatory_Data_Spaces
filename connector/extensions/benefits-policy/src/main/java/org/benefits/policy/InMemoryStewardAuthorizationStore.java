package org.benefits.policy;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryStewardAuthorizationStore implements StewardAuthorizationStore {

    private final Map<String, StewardAuthorization> byReleaseRequest = new ConcurrentHashMap<>();

    @Override
    @Nullable
    public StewardAuthorization findByReleaseRequestId(String releaseRequestId) {
        return byReleaseRequest.get(releaseRequestId);
    }

    @Override
    public void save(StewardAuthorization authorization) {
        byReleaseRequest.put(authorization.releaseRequestId(), authorization);
    }
}
