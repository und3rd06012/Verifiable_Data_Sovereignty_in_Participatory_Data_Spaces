package org.benefits.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConsentGrantTest {

    @Test
    void isASimpleValueRecordWithAllSixFields() {
        var grant = new ConsentGrant("serviceType", "community-insight", "community",
                "2020-01-01T00:00:00Z", null, null);

        assertThat(grant.field()).isEqualTo("serviceType");
        assertThat(grant.purpose()).isEqualTo("community-insight");
        assertThat(grant.recipientClass()).isEqualTo("community");
        assertThat(grant.grantedAt()).isEqualTo("2020-01-01T00:00:00Z");
        assertThat(grant.expiresAt()).isNull();
        assertThat(grant.revokedAt()).isNull();
    }
}
