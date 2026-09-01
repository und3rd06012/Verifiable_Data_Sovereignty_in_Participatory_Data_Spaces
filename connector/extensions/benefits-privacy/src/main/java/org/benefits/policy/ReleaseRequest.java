package org.benefits.policy;

import java.time.Instant;
import java.util.List;

public record ReleaseRequest(
        String releaseRequestId,
        String purpose,
        String recipientClass,
        List<String> fields,
        String siteId,
        Instant requestedAt) {

    public ReleaseRequest {
        fields = List.copyOf(fields);
    }
}
