package org.benefits.experiments.e4;

import org.benefits.policy.Contribution;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SeededPopulationGeneratorTest {

    @Test
    void sameSeedProducesIdenticalPopulations() {
        var a = new SeededPopulationGenerator(42).generate(100, false);
        var b = new SeededPopulationGenerator(42).generate(100, false);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentSeedsProduceDifferentPopulations() {
        var a = new SeededPopulationGenerator(42).generate(100, false);
        var b = new SeededPopulationGenerator(43).generate(100, false);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void uniformAndSkewedDifferForTheSameSeed() {
        var uniform = new SeededPopulationGenerator(42).generate(300, false);
        var skewed = new SeededPopulationGenerator(42).generate(300, true);
        assertThat(uniform).isNotEqualTo(skewed);
    }

    @Test
    void producesTheRequestedPopulationSize() {
        var population = new SeededPopulationGenerator(1).generate(37, false);
        assertThat(population).hasSize(37);
    }

    @Test
    void everyContributionHasAllThreeAttributesAndTheSharedSiteId() {
        var population = new SeededPopulationGenerator(1).generate(50, false);
        for (var contribution : population) {
            assertThat(contribution.siteId()).isEqualTo(SeededPopulationGenerator.SITE_ID);
            assertThat(contribution.attribute("serviceType")).isNotEqualTo(Contribution.ABSENT);
            assertThat(contribution.attribute("channel")).isNotEqualTo(Contribution.ABSENT);
            assertThat(contribution.attribute("urgencyLevel")).isNotEqualTo(Contribution.ABSENT);
        }
    }

    @Test
    void everyContributionHasADistinctParticipantRef() {
        var population = new SeededPopulationGenerator(1).generate(200, false);
        var distinctRefs = population.stream().map(Contribution::participantRef).collect(Collectors.toSet());
        assertThat(distinctRefs).hasSize(200);
    }

    @Test
    void skewedChannelLeansTowardPaperAcrossALargePopulation() {
        var population = new SeededPopulationGenerator(1).generate(10000, true);
        var paperCount = population.stream().filter(c -> "paper".equals(c.attribute("channel"))).count();
        // 61% target; a large population keeps sampling noise small relative to that margin.
        assertThat(paperCount).isGreaterThan(5000L);
    }

    @Test
    void uniformChannelIsRoughlyEvenAcrossALargePopulation() {
        var population = new SeededPopulationGenerator(1).generate(10000, false);
        var counts = new java.util.HashMap<String, Long>();
        for (var contribution : population) {
            counts.merge(contribution.attribute("channel"), 1L, Long::sum);
        }
        assertThat(counts).hasSize(4);
        for (var count : counts.values()) {
            assertThat(count).isBetween(2000L, 3000L);
        }
    }
}
