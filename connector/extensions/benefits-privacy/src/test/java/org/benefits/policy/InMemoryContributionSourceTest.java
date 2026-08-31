package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryContributionSourceTest {

    private Contribution c(String participant, String site, String channel) {
        return new Contribution(participant, site, Map.of("channel", channel));
    }

    @Test
    void returnsOnlyContributionsForTheRequestedSite() {
        var source = new InMemoryContributionSource(List.of(
                c("p-1", "athens", "app"),
                c("p-2", "mytilene", "app")));
        assertThat(source.contributionsFor("athens")).hasSize(1);
        assertThat(source.contributionsFor("athens").get(0).participantRef()).isEqualTo("p-1");
    }

    @Test
    void returnsEmptyForAnUnknownSite() {
        var source = new InMemoryContributionSource(List.of(c("p-1", "athens", "app")));
        assertThat(source.contributionsFor("nowhere")).isEmpty();
    }
}
