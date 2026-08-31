package org.benefits.policy;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

public record StewardAuthorization(
        String releaseRequestId,
        String datasetHash,
        List<StewardSignature> signatures,
        Instant issuedAt) {

    public StewardAuthorization {
        signatures = List.copyOf(signatures);
    }

    public int distinctStewardCount() {
        return signatures.stream()
                .map(StewardSignature::stewardId)
                .collect(Collectors.toSet())
                .size();
    }
}
