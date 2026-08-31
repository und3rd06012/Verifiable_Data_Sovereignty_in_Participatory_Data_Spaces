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

    private KAnonymity() {
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
                query.groupBy().stream().map(contribution::attribute).toList());
    }
}
