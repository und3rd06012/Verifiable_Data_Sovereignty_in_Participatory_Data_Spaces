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
            json.add("{\"group\":\"" + group.groupKey() + "\",\"count\":" + noised + "}");
        }

        return Optional.of(new ReleaseArtifact(releaseRequestId,
                json.toString().getBytes(StandardCharsets.UTF_8), computedK, epsilon));
    }
}
