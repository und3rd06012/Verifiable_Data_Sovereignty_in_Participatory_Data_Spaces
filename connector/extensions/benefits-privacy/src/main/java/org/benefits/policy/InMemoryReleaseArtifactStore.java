package org.benefits.policy;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryReleaseArtifactStore implements ReleaseArtifactStore {

    private final Map<String, ReleaseArtifact> byReleaseRequest = new ConcurrentHashMap<>();

    @Override
    @Nullable
    public ReleaseArtifact findByReleaseRequestId(String releaseRequestId) {
        return byReleaseRequest.get(releaseRequestId);
    }

    @Override
    public void save(ReleaseArtifact artifact) {
        byReleaseRequest.put(artifact.releaseRequestId(), artifact);
    }
}
