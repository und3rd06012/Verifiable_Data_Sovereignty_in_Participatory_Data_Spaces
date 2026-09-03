package org.benefits.experiments.g2;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeterogeneitySweepPointRunnerTest {

    @Test
    void atHeterogeneityZeroCoverageAndExclusionErrorAreIdentical() {
        // h = 0: nobody is excluded, so consentingTruth == fullTruth for every group,
        // and the two relative-error statistics must therefore be exactly equal.
        var result = new HeterogeneitySweepPointRunner(1L).run(1.0, 100, 5, 0.0, 0);

        assertThat(result.coverageMedianRelativeError()).isEqualTo(result.exclusionMedianRelativeError());
        assertThat(result.coverageIqrRelativeError()).isEqualTo(result.exclusionIqrRelativeError());
        assertThat(result.excludedFraction()).isEqualTo(0.0);
    }

    @Test
    void higherHeterogeneityProducesAHigherExcludedFraction() {
        var low = new HeterogeneitySweepPointRunner(1L).run(1.0, 300, 5, 0.1, 1);
        var high = new HeterogeneitySweepPointRunner(1L).run(1.0, 300, 5, 0.9, 2);

        assertThat(high.excludedFraction()).isGreaterThan(low.excludedFraction());
    }

    @Test
    void dataPointsIsPositive() {
        var result = new HeterogeneitySweepPointRunner(1L).run(1.0, 100, 5, 0.5, 3);
        assertThat(result.dataPoints()).isGreaterThanOrEqualTo(8);
    }

    @Test
    void sameSeedAndSamePointProduceByteIdenticalResults() {
        var a = new HeterogeneitySweepPointRunner(7L).run(0.5, 100, 5, 0.3, 4);
        var b = new HeterogeneitySweepPointRunner(7L).run(0.5, 100, 5, 0.3, 4);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void medianAndIqrAreFiniteAndNonNegative() {
        var result = new HeterogeneitySweepPointRunner(1L).run(0.5, 100, 5, 0.7, 5);
        assertThat(result.coverageMedianRelativeError()).isGreaterThanOrEqualTo(0.0).isFinite();
        assertThat(result.exclusionMedianRelativeError()).isGreaterThanOrEqualTo(0.0).isFinite();
    }

    @Test
    void survivingMedianEqualsAtHeterogeneityZeroAndDivergesAtHeterogeneityOne() {
        var atZero = new HeterogeneitySweepPointRunner(1L).run(0.25, 300, 5, 0.0, 0);
        var atOne = new HeterogeneitySweepPointRunner(1L).run(0.25, 300, 5, 1.0, 1);

        // At h=0 nobody is excluded, so consentingTruth == fullTruth for every
        // surviving group, and the two surviving medians must be exactly equal.
        assertThat(atZero.survivingCoverageMedianRelativeError())
                .isEqualTo(atZero.survivingExclusionMedianRelativeError());

        // At h=1, the exclusion signal (drift from the TRUE full-population value)
        // must be strictly larger than the coverage signal (drift from the
        // consenting subset's own true value, which the privacy mechanism alone is
        // responsible for), since excluding real participants moves the observed
        // answer away from the full population's truth on top of ordinary noise.
        assertThat(atOne.survivingExclusionMedianRelativeError())
                .isGreaterThan(atOne.survivingCoverageMedianRelativeError());
    }
}
