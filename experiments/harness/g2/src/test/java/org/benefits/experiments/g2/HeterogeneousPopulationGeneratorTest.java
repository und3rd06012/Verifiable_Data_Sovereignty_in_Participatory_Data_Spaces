package org.benefits.experiments.g2;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeterogeneousPopulationGeneratorTest {

    @Test
    void atHeterogeneityZeroEveryParticipantHasAllThreeBroadGrants() {
        var population = new HeterogeneousPopulationGenerator(1L).generate(50, 0.0);
        assertThat(population).hasSize(50);
        for (var contribution : population) {
            assertThat(contribution.consentGrants()).hasSize(3);
            var fields = contribution.consentGrants().stream().map(g -> g.field()).toList();
            assertThat(fields).containsExactlyInAnyOrder("serviceType", "channel", "urgencyLevel");
        }
    }

    @Test
    void atHeterogeneityOneEveryParticipantIsMissingExactlyOneField() {
        var population = new HeterogeneousPopulationGenerator(1L).generate(50, 1.0);
        for (var contribution : population) {
            assertThat(contribution.consentGrants()).hasSize(2);
        }
    }

    @Test
    void everyGrantCoversTheFixedPurposeAndRecipientClass() {
        var population = new HeterogeneousPopulationGenerator(1L).generate(10, 0.5);
        for (var contribution : population) {
            for (var grant : contribution.consentGrants()) {
                assertThat(grant.purpose()).isEqualTo("community-insight");
                assertThat(grant.recipientClass()).isEqualTo("community");
                assertThat(grant.expiresAt()).isNull();
                assertThat(grant.revokedAt()).isNull();
            }
        }
    }

    @Test
    void attributesStillMatchWhatSeededPopulationGeneratorWouldProduce() {
        var fromG2 = new HeterogeneousPopulationGenerator(1L).generate(50, 0.0);
        var fromE4 = new org.benefits.experiments.e4.SeededPopulationGenerator(1L).generate(50, false);

        assertThat(fromG2).hasSameSizeAs(fromE4);
        for (var i = 0; i < fromG2.size(); i++) {
            assertThat(fromG2.get(i).participantRef()).isEqualTo(fromE4.get(i).participantRef());
            assertThat(fromG2.get(i).attribute("serviceType")).isEqualTo(fromE4.get(i).attribute("serviceType"));
            assertThat(fromG2.get(i).attribute("channel")).isEqualTo(fromE4.get(i).attribute("channel"));
            assertThat(fromG2.get(i).attribute("urgencyLevel")).isEqualTo(fromE4.get(i).attribute("urgencyLevel"));
        }
    }

    @Test
    void sameSeedAndSameHProduceByteIdenticalPopulations() {
        var a = new HeterogeneousPopulationGenerator(5L).generate(30, 0.3);
        var b = new HeterogeneousPopulationGenerator(5L).generate(30, 0.3);
        assertThat(a).isEqualTo(b);
    }
}
