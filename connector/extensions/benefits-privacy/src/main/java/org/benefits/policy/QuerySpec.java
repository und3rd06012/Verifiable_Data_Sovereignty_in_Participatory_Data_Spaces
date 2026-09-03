package org.benefits.policy;

import java.util.List;

/**
 * A grouped count query. Means and sums are deliberately not supported: their
 * sensitivity depends on the value range, and an incorrect range silently
 * under-noises the result.
 */
public record QuerySpec(String siteId, List<String> groupBy, String purpose, String recipientClass) {

    public QuerySpec {
        groupBy = List.copyOf(groupBy);
    }

    public QuerySpec(String siteId, List<String> groupBy) {
        this(siteId, groupBy, null, null);
    }
}
