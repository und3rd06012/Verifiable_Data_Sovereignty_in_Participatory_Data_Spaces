package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySpecTest {

    @Test
    void theLegacyTwoArgConstructorLeavesPurposeAndRecipientClassNull() {
        var query = new QuerySpec("site-1", List.of("channel"));
        assertThat(query.purpose()).isNull();
        assertThat(query.recipientClass()).isNull();
    }

    @Test
    void theFourArgConstructorSetsPurposeAndRecipientClass() {
        var query = new QuerySpec("site-1", List.of("channel"), "community-insight", "community");
        assertThat(query.purpose()).isEqualTo("community-insight");
        assertThat(query.recipientClass()).isEqualTo("community");
    }

    @Test
    void groupByIsStillDefensivelyCopied() {
        var groupBy = new java.util.ArrayList<String>();
        groupBy.add("channel");
        var query = new QuerySpec("site-1", groupBy);
        groupBy.clear();
        assertThat(query.groupBy()).containsExactly("channel");
    }
}
