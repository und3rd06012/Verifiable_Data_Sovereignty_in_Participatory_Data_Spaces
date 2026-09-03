package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContributionTest {

    @Test
    void theLegacyThreeArgConstructorLeavesConsentGrantsNull() {
        var contribution = new Contribution("p-1", "site-1", Map.of("channel", "app"));
        assertThat(contribution.consentGrants()).isNull();
    }

    @Test
    void theFourArgConstructorCopiesTheGrantsListDefensively() {
        var grants = new java.util.ArrayList<ConsentGrant>();
        grants.add(new ConsentGrant("channel", "p", "r", "2020-01-01T00:00:00Z", null, null));
        var contribution = new Contribution("p-1", "site-1", Map.of("channel", "app"), grants);
        grants.clear();
        assertThat(contribution.consentGrants()).hasSize(1);
    }

    @Test
    void anEmptyNonNullGrantsListIsPreservedAsEmptyNotNull() {
        var contribution = new Contribution("p-1", "site-1", Map.of(), List.of());
        assertThat(contribution.consentGrants()).isNotNull();
        assertThat(contribution.consentGrants()).isEmpty();
    }

    @Test
    void attributeStillReturnsTheAbsentSentinelForAMissingKey() {
        var contribution = new Contribution("p-1", "site-1", Map.of());
        assertThat(contribution.attribute("serviceType")).isEqualTo(Contribution.ABSENT);
    }
}
