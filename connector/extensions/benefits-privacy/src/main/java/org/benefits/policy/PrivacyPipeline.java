package org.benefits.policy;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Composes k-anonymity and Laplace noise into a stored artifact, reserving privacy
 * budget before doing any work that would be released.
 */
public class PrivacyPipeline {

    private final ContributionSource contributions;
    private final NoiseSource noise;
    private final BudgetAccountant accountant;

    public PrivacyPipeline(ContributionSource contributions, NoiseSource noise, BudgetAccountant accountant) {
        this.contributions = contributions;
        this.noise = noise;
        this.accountant = accountant;
    }

    /**
     * Returns empty when the site's remaining privacy budget cannot cover epsilon.
     * Nothing is charged in that case.
     */
    public Optional<ReleaseArtifact> compute(String releaseRequestId, QuerySpec query, int k, double epsilon) {
        if (!accountant.reserve(query.siteId(), epsilon)) {
            return Optional.empty();
        }

        var groups = KAnonymity.aggregate(contributions.contributionsFor(query.siteId()), query, k);

        var computedK = groups.stream().mapToInt(GroupedCount::count).min().orElse(0);

        var json = new StringJoiner(",", "{\"groups\":[", "]}");
        for (var group : groups) {
            var noised = group.count() + noise.noise(epsilon);
            json.add("{\"group\":\"" + jsonEscape(group.groupKey()) + "\",\"count\":" + noised + "}");
        }

        return Optional.of(new ReleaseArtifact(releaseRequestId,
                json.toString().getBytes(StandardCharsets.UTF_8), computedK, epsilon));
    }

    /**
     * Escapes a string for embedding in a JSON string literal.
     * <p>
     * The group key already carries this pipeline's own separator escaping, which uses
     * backslashes. Those are not valid JSON escapes on their own, so writing a key into
     * the document unescaped can produce an unparseable document, or one a parser silently
     * reinterprets.
     */
    private static String jsonEscape(String value) {
        var out = new StringBuilder(value.length() + 8);
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
