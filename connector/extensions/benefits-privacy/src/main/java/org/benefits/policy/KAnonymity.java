package org.benefits.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups contributions and suppresses groups whose distinct-participant count is
 * below k.
 * <p>
 * Two properties matter and are tested. Counting is by DISTINCT participant, not by
 * row: the sensitivity-of-1 calibration the Laplace noise depends on is only correct
 * if one participant can move a group's count by at most one. And the output is
 * sorted by group key, so a stored artifact is byte-reproducible.
 * <p>
 * Suppression runs on TRUE counts. Which groups survive is therefore not itself
 * differentially private; only the released values are. This is a known and
 * deliberate limitation, recorded in the design spec.
 */
public final class KAnonymity {

    private static final String KEY_SEPARATOR = "|";
    private static final String KEY_ESCAPE = "\\";

    private KAnonymity() {
    }

    /**
     * Escapes a value so that the joined group key is injective.
     * <p>
     * Without this, a value containing the separator would collide two distinct groups
     * into one key. That is a privacy failure rather than a correctness one: two groups
     * each below k, merged, can clear k and be released, and an adversary who can bound
     * one component recovers the other by subtraction.
     */
    private static String escape(String value) {
        return value.replace(KEY_ESCAPE, KEY_ESCAPE + KEY_ESCAPE)
                .replace(KEY_SEPARATOR, KEY_ESCAPE + KEY_SEPARATOR);
    }

    public static List<GroupedCount> aggregate(List<Contribution> contributions, QuerySpec query, int k) {
        Map<String, Set<String>> participantsByGroup = new HashMap<>();

        for (var contribution : contributions) {
            var key = groupKey(contribution, query);
            participantsByGroup.computeIfAbsent(key, unused -> new HashSet<>())
                    .add(contribution.participantRef());
        }

        List<GroupedCount> surviving = new ArrayList<>();
        for (var entry : participantsByGroup.entrySet()) {
            var distinct = entry.getValue().size();
            if (distinct >= k) {
                surviving.add(new GroupedCount(entry.getKey(), distinct));
            }
        }

        surviving.sort(Comparator.comparing(GroupedCount::groupKey));
        return List.copyOf(surviving);
    }

    private static String groupKey(Contribution contribution, QuerySpec query) {
        return String.join(KEY_SEPARATOR,
                query.groupBy().stream().map(contribution::attribute).map(KAnonymity::escape).toList());
    }
}
