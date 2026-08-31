package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class KAnonymityTest {

    private Contribution c(String participant, String channel) {
        return new Contribution(participant, "athens", Map.of("channel", channel));
    }

    private List<Contribution> many(String channel, int n, String prefix) {
        return IntStream.range(0, n).mapToObj(i -> c(prefix + i, channel)).toList();
    }

    private final QuerySpec byChannel = new QuerySpec("athens", List.of("channel"));

    @Test
    void suppressesAGroupBelowK() {
        var result = KAnonymity.aggregate(many("app", 4, "a"), byChannel, 5);
        assertThat(result).isEmpty();
    }

    @Test
    void keepsAGroupExactlyAtK() {
        var result = KAnonymity.aggregate(many("app", 5, "a"), byChannel, 5);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).count()).isEqualTo(5);
    }

    @Test
    void suppressesOnlyTheSmallGroup() {
        var mixed = new java.util.ArrayList<Contribution>(many("app", 6, "a"));
        mixed.addAll(many("sms", 2, "s"));
        var result = KAnonymity.aggregate(mixed, byChannel, 5);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).groupKey()).isEqualTo("app");
    }

    @Test
    void countsDistinctParticipantsNotRows() {
        // one participant contributing three rows must count once, or the
        // sensitivity-of-1 assumption the noise calibration rests on is false
        var repeated = List.of(c("p-1", "app"), c("p-1", "app"), c("p-1", "app"),
                c("p-2", "app"), c("p-3", "app"), c("p-4", "app"), c("p-5", "app"));
        var result = KAnonymity.aggregate(repeated, byChannel, 5);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).count()).isEqualTo(5);
    }

    @Test
    void groupsBySeveralAttributesInOrder() {
        var spec = new QuerySpec("athens", List.of("channel", "service"));
        var rows = IntStream.range(0, 5)
                .mapToObj(i -> new Contribution("p" + i, "athens",
                        Map.of("channel", "app", "service", "housing")))
                .toList();
        var result = KAnonymity.aggregate(rows, spec, 5);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).groupKey()).isEqualTo("app|housing");
    }

    @Test
    void anEmptyGroupByProducesOneTotalGroup() {
        var spec = new QuerySpec("athens", List.of());
        var result = KAnonymity.aggregate(many("app", 7, "a"), spec, 5);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).groupKey()).isEmpty();
        assertThat(result.get(0).count()).isEqualTo(7);
    }

    @Test
    void resultIsSortedByGroupKeyForDeterminism() {
        var mixed = new java.util.ArrayList<Contribution>(many("zeta", 5, "z"));
        mixed.addAll(many("alpha", 5, "a"));
        var result = KAnonymity.aggregate(mixed, byChannel, 5);
        assertThat(result).extracting(GroupedCount::groupKey).containsExactly("alpha", "zeta");
    }
}
