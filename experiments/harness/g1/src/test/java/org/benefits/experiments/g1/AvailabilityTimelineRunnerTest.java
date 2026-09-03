package org.benefits.experiments.g1;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityTimelineRunnerTest {

    @Test
    void certainActionEveryTickReachesQuorumOnTheFirstTick() {
        // p_act = 1.0: every steward acts on tick 1. veto_share = 0.0: every action is
        // a signature. With N=5, quorum=3, Arm A must reach quorum at tick 1.
        var outcome = new AvailabilityTimelineRunner().run(5, 3, 1.0, 0.0, 42L);
        assertThat(outcome.armATicksToQuorum()).isEqualTo(1);
    }

    @Test
    void zeroActionProbabilityNeverReachesQuorumAndStarves() {
        var outcome = new AvailabilityTimelineRunner().run(5, 3, 0.0, 0.0, 42L);
        assertThat(outcome.armATicksToQuorum()).isNull();
    }

    @Test
    void sameInputsProduceByteIdenticalOutcomes() {
        var a = new AvailabilityTimelineRunner().run(10, 5, 0.3, 0.1, 777L);
        var b = new AvailabilityTimelineRunner().run(10, 5, 0.3, 0.1, 777L);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void aQuorumOfOneIsReachedAsSoonAsAnySingleStewardActs() {
        // With veto_share = 0.0 every action is a signature, so the very first steward
        // to act at all satisfies quorum=1. p_act is low enough that this typically
        // does not happen on tick 1, giving a real, checkable tick number.
        var outcome = new AvailabilityTimelineRunner().run(20, 1, 0.05, 0.0, 5L);
        assertThat(outcome.armATicksToQuorum()).isNotNull();
        assertThat(outcome.armATicksToQuorum()).isGreaterThanOrEqualTo(1);
    }
}
