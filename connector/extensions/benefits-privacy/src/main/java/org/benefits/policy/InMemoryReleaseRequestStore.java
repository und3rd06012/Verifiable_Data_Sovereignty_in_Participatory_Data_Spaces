package org.benefits.policy;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryReleaseRequestStore implements ReleaseRequestStore {

    private final Map<String, ReleaseRequest> byId = new ConcurrentHashMap<>();

    @Override
    @Nullable
    public ReleaseRequest findById(String releaseRequestId) {
        return byId.get(releaseRequestId);
    }

    @Override
    public void save(ReleaseRequest request) {
        byId.put(request.releaseRequestId(), request);
    }

    @Override
    public List<ReleaseRequest> all() {
        return List.copyOf(byId.values());
    }
}
