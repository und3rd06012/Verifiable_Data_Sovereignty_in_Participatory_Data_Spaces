package org.benefits.policy;

import org.eclipse.edc.spi.EdcException;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    @Override
    public StewardAuthorization appendReview(String releaseRequestId, String datasetHash, StewardReview review) {
        return byReleaseRequest.compute(releaseRequestId, (id, existing) -> {
            var base = existing != null ? existing : empty(releaseRequestId, datasetHash);
            return new StewardAuthorization(base.releaseRequestId(), base.datasetHash(), base.signatures(),
                    append(base.reviews(), review), base.vetoes(), base.appeals(), base.issuedAt());
        });
    }

    @Override
    public StewardAuthorization appendSignature(String releaseRequestId, String datasetHash, StewardSignature signature) {
        return byReleaseRequest.compute(releaseRequestId, (id, existing) -> {
            var base = existing != null ? existing : empty(releaseRequestId, datasetHash);
            return new StewardAuthorization(base.releaseRequestId(), base.datasetHash(), append(base.signatures(), signature),
                    base.reviews(), base.vetoes(), base.appeals(), base.issuedAt());
        });
    }

    @Override
    public StewardAuthorization appendVeto(String releaseRequestId, String datasetHash, StewardVeto veto) {
        return byReleaseRequest.compute(releaseRequestId, (id, existing) -> {
            var base = existing != null ? existing : empty(releaseRequestId, datasetHash);
            return new StewardAuthorization(base.releaseRequestId(), base.datasetHash(), base.signatures(),
                    base.reviews(), append(base.vetoes(), veto), base.appeals(), base.issuedAt());
        });
    }

    @Override
    public StewardAuthorization appendAppeal(String releaseRequestId, StewardAppeal appeal) {
        var existing = byReleaseRequest.get(releaseRequestId);
        if (existing == null) {
            throw new EdcException("cannot appeal a veto for release request with no steward authorization: " + releaseRequestId);
        }
        var vetoExists = existing.vetoes().stream().anyMatch(v -> v.stewardId().equals(appeal.vetoStewardId()));
        if (!vetoExists) {
            throw new EdcException("no veto by " + appeal.vetoStewardId() + " to appeal for release request " + releaseRequestId);
        }
        return byReleaseRequest.compute(releaseRequestId, (id, current) -> new StewardAuthorization(
                current.releaseRequestId(), current.datasetHash(), current.signatures(),
                current.reviews(), current.vetoes(), append(current.appeals(), appeal), current.issuedAt()));
    }

    private static StewardAuthorization empty(String releaseRequestId, String datasetHash) {
        return new StewardAuthorization(releaseRequestId, datasetHash, List.of(), List.of(), List.of(), List.of(), Instant.now());
    }

    private static <T> List<T> append(List<T> list, T item) {
        var copy = new ArrayList<>(list);
        copy.add(item);
        return List.copyOf(copy);
    }
}
