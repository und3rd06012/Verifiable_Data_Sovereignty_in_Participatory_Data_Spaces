package org.benefits.experiments.e4;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SweepPointRunnerTest {

    @Test
    void producesTheRequestedSweepPointFields() {
        var result = new SweepPointRunner(1).run(1.0, 100, 5, false, 2, 1, 1, 0);
        assertThat(result.seed()).isEqualTo(1);
        assertThat(result.epsilon()).isEqualTo(1.0);
        assertThat(result.population()).isEqualTo(100);
        assertThat(result.k()).isEqualTo(5);
        assertThat(result.skewed()).isFalse();
    }

    @Test
    void medianAndIqrAreWithinTheBoundedZeroToOneRange() {
        var result = new SweepPointRunner(1).run(0.5, 100, 5, false, 1, 1, 1, 0);
        assertThat(result.medianRelativeError()).isBetween(0.0, 1.0);
        assertThat(result.iqrRelativeError()).isBetween(0.0, 1.0);
        assertThat(result.suppressionRefusalRate()).isBetween(0.0, 1.0);
    }

    @Test
    void dataPointsIsPositiveAndReflectsThePooledGroupAndProportionCount() {
        var result = new SweepPointRunner(1).run(1.0, 300, 3, false, 2, 3, 0, 0);
        // At least one data point per query in the eight-query workload.
        assertThat(result.dataPoints()).isGreaterThanOrEqualTo(8);
    }

    @Test
    void sameSeedAndSamePointProduceByteIdenticalResults() {
        var a = new SweepPointRunner(7).run(0.5, 200, 5, true, 1, 2, 1, 1);
        var b = new SweepPointRunner(7).run(0.5, 200, 5, true, 1, 2, 1, 1);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void aHighEpsilonWithASmallPopulationProducesSomeRefusalOrSuppression() {
        // At epsilon=2.0, the shared 2.0 total budget (spec section 6) allows only the
        // first query to succeed; the remaining seven are refused outright, so the
        // suppression/refusal rate must be well above zero regardless of population or k.
        var result = new SweepPointRunner(1).run(2.0, 30, 10, false, 4, 0, 3, 0);
        assertThat(result.suppressionRefusalRate()).isGreaterThan(0.5);
    }

    @Test
    void aLowEpsilonAnswersEveryQueryWithNoRefusals() {
        // At epsilon=0.1, the shared 2.0 total budget covers all eight queries
        // (8 x 0.1 = 0.8 < 2.0), so nothing is refused for budget reasons; a large
        // population and low k also keep suppression unlikely, isolating the refusal
        // component of the statistic at (close to) zero.
        var result = new SweepPointRunner(1).run(0.1, 300, 3, false, 0, 3, 0, 0);
        assertThat(result.suppressionRefusalRate()).isLessThan(0.2);
    }
}
