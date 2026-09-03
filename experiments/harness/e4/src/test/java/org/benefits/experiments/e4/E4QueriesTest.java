package org.benefits.experiments.e4;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class E4QueriesTest {

    @Test
    void workloadHasExactlyEightQueriesInTheSpecifiedOrder() {
        var workload = E4Queries.workload();
        assertThat(workload).hasSize(8);
        assertThat(workload.stream().map(E4Queries.E4Query::name)).containsExactly(
                "count-service-type",
                "count-channel",
                "count-urgency",
                "proportion-largest-service-type",
                "proportion-legal-aid",
                "crosstab-service-type-channel",
                "crosstab-service-type-urgency",
                "crosstab-service-type-channel-urgency"
        );
    }

    @Test
    void threeCountQueriesGroupByExactlyOneAttribute() {
        var workload = E4Queries.workload();
        assertThat(workload.get(0).spec().groupBy()).containsExactly("serviceType");
        assertThat(workload.get(1).spec().groupBy()).containsExactly("channel");
        assertThat(workload.get(2).spec().groupBy()).containsExactly("urgencyLevel");
    }

    @Test
    void threeCrosstabQueriesGroupByIncreasingAttributeCounts() {
        var workload = E4Queries.workload();
        assertThat(workload.get(5).spec().groupBy()).containsExactly("serviceType", "channel");
        assertThat(workload.get(6).spec().groupBy()).containsExactly("serviceType", "urgencyLevel");
        assertThat(workload.get(7).spec().groupBy()).containsExactly("serviceType", "channel", "urgencyLevel");
    }

    @Test
    void proportionQueriesAreMarkedWithTheCorrectKind() {
        var workload = E4Queries.workload();
        assertThat(workload.get(3).kind()).isEqualTo(E4Queries.Kind.PROPORTION_LARGEST);
        assertThat(workload.get(4).kind()).isEqualTo(E4Queries.Kind.PROPORTION_TARGET);
        assertThat(workload.get(4).targetCategory()).isEqualTo("legal-aid");
    }
}
