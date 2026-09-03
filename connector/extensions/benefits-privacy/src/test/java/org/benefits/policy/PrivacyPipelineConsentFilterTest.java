package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real PrivacyPipeline.compute filter directly with hand-built consent
 * grants, confirming both the new behavior and that it is unreachable for any query
 * that does not declare a purpose/recipientClass (spec section 4.1).
 */
class PrivacyPipelineConsentFilterTest {

    private static Contribution withGrant(String participantRef, String value, boolean valid) {
        var grants = valid
                ? List.of(new ConsentGrant("serviceType", "community-insight", "community",
                        "2020-01-01T00:00:00Z", null, null))
                : List.<ConsentGrant>of();
        return new Contribution(participantRef, "site-1", Map.of("serviceType", value), grants);
    }

    @Test
    void aQueryWithNoPurposeOrRecipientClassIncludesEveryContributionRegardlessOfConsent() {
        var contributions = List.of(
                withGrant("p-1", "housing", true),
                withGrant("p-2", "housing", false));
        var source = new InMemoryContributionSource(contributions);
        var pipeline = new PrivacyPipeline(source, new LaplaceNoiseSource(new Random(1)),
                new InMemoryBudgetAccountant(2.0));

        // No purpose/recipientClass: the legacy two-arg QuerySpec constructor, matching
        // every existing caller (E1/E3/E4/ReleaseRequestResource).
        var released = pipeline.compute("t-1", new QuerySpec("site-1", List.of("serviceType")), 1, 1.0);

        assertThat(released).isPresent();
        assertThat(released.get().computedK()).isEqualTo(2);
    }

    @Test
    void aQueryWithPurposeAndRecipientClassExcludesContributionsMissingAValidGrant() {
        var contributions = List.of(
                withGrant("p-1", "housing", true),
                withGrant("p-2", "housing", false));
        var source = new InMemoryContributionSource(contributions);
        var pipeline = new PrivacyPipeline(source, new LaplaceNoiseSource(new Random(1)),
                new InMemoryBudgetAccountant(2.0));

        var query = new QuerySpec("site-1", List.of("serviceType"), "community-insight", "community");
        var released = pipeline.compute("t-2", query, 1, 1.0);

        assertThat(released).isPresent();
        // Only p-1 has a valid grant; p-2 is excluded before k-anonymity even runs.
        assertThat(released.get().computedK()).isEqualTo(1);
    }

    @Test
    void aContributionWithNullConsentGrantsIsAlwaysIncludedEvenUnderAScopedQuery() {
        var untracked = new Contribution("p-3", "site-1", Map.of("serviceType", "housing"));
        var withValidGrant = withGrant("p-4", "housing", true);
        var source = new InMemoryContributionSource(List.of(untracked, withValidGrant));
        var pipeline = new PrivacyPipeline(source, new LaplaceNoiseSource(new Random(1)),
                new InMemoryBudgetAccountant(2.0));

        var query = new QuerySpec("site-1", List.of("serviceType"), "community-insight", "community");
        var released = pipeline.compute("t-3", query, 1, 1.0);

        assertThat(released).isPresent();
        assertThat(released.get().computedK()).isEqualTo(2);
    }

    @Test
    void anExpiredGrantDoesNotCoverTheQuery() {
        var expired = new Contribution("p-5", "site-1", Map.of("serviceType", "housing"),
                List.of(new ConsentGrant("serviceType", "community-insight", "community",
                        "2020-01-01T00:00:00Z", "2020-06-01T00:00:00Z", null)));
        var valid = withGrant("p-6", "housing", true);
        var source = new InMemoryContributionSource(List.of(expired, valid));
        var pipeline = new PrivacyPipeline(source, new LaplaceNoiseSource(new Random(1)),
                new InMemoryBudgetAccountant(2.0));

        var query = new QuerySpec("site-1", List.of("serviceType"), "community-insight", "community");
        var released = pipeline.compute("t-4", query, 1, 1.0);

        assertThat(released).isPresent();
        assertThat(released.get().computedK()).isEqualTo(1);
    }

    @Test
    void aRevokedGrantDoesNotCoverTheQueryEvenIfNotExpired() {
        var revoked = new Contribution("p-7", "site-1", Map.of("serviceType", "housing"),
                List.of(new ConsentGrant("serviceType", "community-insight", "community",
                        "2020-01-01T00:00:00Z", null, "2020-06-01T00:00:00Z")));
        var valid = withGrant("p-8", "housing", true);
        var source = new InMemoryContributionSource(List.of(revoked, valid));
        var pipeline = new PrivacyPipeline(source, new LaplaceNoiseSource(new Random(1)),
                new InMemoryBudgetAccountant(2.0));

        var query = new QuerySpec("site-1", List.of("serviceType"), "community-insight", "community");
        var released = pipeline.compute("t-5", query, 1, 1.0);

        assertThat(released).isPresent();
        assertThat(released.get().computedK()).isEqualTo(1);
    }

    @Test
    void multiFieldGroupByRequiresAValidGrantForEveryGroupByField() {
        var partial = new Contribution("p-9", "site-1", Map.of("serviceType", "housing", "channel", "app"),
                List.of(new ConsentGrant("serviceType", "community-insight", "community",
                        "2020-01-01T00:00:00Z", null, null)));
        // p-9 has a grant for serviceType but not channel: excluded from a query
        // grouping by both.
        var full = new Contribution("p-10", "site-1", Map.of("serviceType", "housing", "channel", "app"),
                List.of(
                        new ConsentGrant("serviceType", "community-insight", "community",
                                "2020-01-01T00:00:00Z", null, null),
                        new ConsentGrant("channel", "community-insight", "community",
                                "2020-01-01T00:00:00Z", null, null)));
        var source = new InMemoryContributionSource(List.of(partial, full));
        var pipeline = new PrivacyPipeline(source, new LaplaceNoiseSource(new Random(1)),
                new InMemoryBudgetAccountant(2.0));

        var query = new QuerySpec("site-1", List.of("serviceType", "channel"), "community-insight", "community");
        var released = pipeline.compute("t-6", query, 1, 1.0);

        assertThat(released).isPresent();
        assertThat(released.get().computedK()).isEqualTo(1);
    }
}
