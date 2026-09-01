package org.benefits.policy;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

public record StewardAuthorization(
        String releaseRequestId,
        String datasetHash,
        List<StewardSignature> signatures,
        List<StewardReview> reviews,
        List<StewardVeto> vetoes,
        List<StewardAppeal> appeals,
        Instant issuedAt) {

    public StewardAuthorization {
        signatures = List.copyOf(signatures);
        reviews = List.copyOf(reviews);
        vetoes = List.copyOf(vetoes);
        appeals = List.copyOf(appeals);
    }

    public int distinctStewardCount() {
        return signatures.stream()
                .map(StewardSignature::stewardId)
                .collect(Collectors.toSet())
                .size();
    }
}
