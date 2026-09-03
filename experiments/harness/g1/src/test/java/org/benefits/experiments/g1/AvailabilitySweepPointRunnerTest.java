package org.benefits.experiments.g1;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilitySweepPointRunnerTest {

    @Test
    void certainActionAndNoVetoesAlwaysReachesQuorumOnTickOneForBothArms() {
        var result = new AvailabilitySweepPointRunner(1L)
                .run(5, 0.5, 1.0, 0.0, 0, 50);

        assertThat(result.runs()).isEqualTo(50);
        assertThat(result.quorumCount()).isEqualTo(3);
        assertThat(result.armAReachedRate()).isEqualTo(1.0);
        assertThat(result.armAStarvedRate()).isEqualTo(0.0);
        assertThat(result.armAMedianTicksToQuorum()).isEqualTo(1.0);
        assertThat(result.armBReachedRate()).isEqualTo(1.0);
        assertThat(result.armBVetoedRate()).isEqualTo(0.0);
    }

    @Test
    void zeroActionProbabilityStarvesBothArmsEveryRun() {
        var result = new AvailabilitySweepPointRunner(1L)
                .run(5, 0.5, 0.0, 0.0, 1, 50);

        assertThat(result.armAReachedRate()).isEqualTo(0.0);
        assertThat(result.armAStarvedRate()).isEqualTo(1.0);
        assertThat(result.armAMedianTicksToQuorum()).isNull();
        assertThat(result.armAIqrTicksToQuorum()).isNull();
        assertThat(result.armBReachedRate()).isEqualTo(0.0);
        assertThat(result.armBVetoedRate()).isEqualTo(0.0);
        assertThat(result.armBStarvedRate()).isEqualTo(1.0);
    }

    @Test
    void quorumCountRoundsTheFractionAndIsAtLeastOne() {
        var result = new AvailabilitySweepPointRunner(1L).run(5, 0.3, 0.5, 0.0, 2, 10);
        // round(5 * 0.3) = round(1.5) = 2 under Java's Math.round half-up convention.
        assertThat(result.quorumCount()).isEqualTo(2);

        var resultRoundingToZero = new AvailabilitySweepPointRunner(1L).run(3, 0.1, 0.5, 0.0, 3, 10);
        // round(3 * 0.1) = round(0.3) = 0, clamped up to 1.
        assertThat(resultRoundingToZero.quorumCount()).isEqualTo(1);
    }

    @Test
    void differentPointIndicesWithTheSameOtherParametersProduceDifferentRunOutcomes() {
        // Confirms per-point seed mixing actually varies the outcome, not just a
        // structural sanity check: with a moderate p_act, two different pointIndex
        // values must not produce identical reached-rate statistics across 200 runs.
        var a = new AvailabilitySweepPointRunner(1L).run(10, 0.5, 0.3, 0.1, 10, 200);
        var b = new AvailabilitySweepPointRunner(1L).run(10, 0.5, 0.3, 0.1, 11, 200);
        assertThat(a.armAMedianTicksToQuorum()).isNotEqualTo(b.armAMedianTicksToQuorum());
    }

    @Test
    void sameBaseSeedAndPointIndexProduceByteIdenticalResults() {
        var a = new AvailabilitySweepPointRunner(7L).run(10, 0.5, 0.3, 0.1, 5, 100);
        var b = new AvailabilitySweepPointRunner(7L).run(10, 0.5, 0.3, 0.1, 5, 100);
        assertThat(a).isEqualTo(b);
    }
}
