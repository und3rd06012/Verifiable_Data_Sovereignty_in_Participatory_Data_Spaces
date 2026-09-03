package org.benefits.experiments.e4;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
    void medianAndIqrAreNonNegativeAndFinite() {
        // Relative error for a surviving-but-noisy group is NOT capped at 1.0 (Laplace
        // noise has unbounded support), so median/IQR are not bounded to [0, 1] in
        // general; only non-negativity and finiteness are guaranteed properties.
        var result = new SweepPointRunner(1).run(0.5, 100, 5, false, 1, 1, 1, 0);
        assertThat(result.medianRelativeError()).isGreaterThanOrEqualTo(0.0);
        assertThat(result.medianRelativeError()).isFinite();
        assertThat(result.iqrRelativeError()).isGreaterThanOrEqualTo(0.0);
        assertThat(result.iqrRelativeError()).isFinite();
        assertThat(result.suppressionRefusalRate()).isBetween(0.0, 1.0);
    }

    @Test
    void negativeNoisedProportionDenominatorIsTreatedAsSuppressedNotAsAComputedRelativeError() {
        // Traced scenario (re-traced after the mix64 seed-derivation fix changed which
        // seed/index tuples hit this path): at epsilon=0.01, population=10, k=3, this exact
        // seed/point hits a proportion query with a negative sum of noised group counts
        // (observedTotal < 0; PrivacyPipeline never clamps noised counts). The widened
        // "observedTotal <= 0.0" guard treats this the same as a suppressed/refused query
        // (relative error sentinel 1.0, counted in suppressionRefusalRate) instead of
        // computing a nonsensical negative-denominator "observed proportion". Reverting the
        // guard to the original "observedTotal == 0.0" check on this exact scenario
        // (verified against the real, temporarily-reverted production code) measurably
        // lowers suppressionRefusalRate (traced at 0.8, 32/40) versus the fixed 0.85 (34/40)
        // asserted here, proving the widened guard is actually exercised in this scenario.
        var result = new SweepPointRunner(1).run(0.01, 10, 3, false, 0, 0, 0, 0);
        assertThat(result.dataPoints()).isEqualTo(40);
        assertThat(result.suppressionRefusalRate()).isCloseTo(0.85, within(1e-9));
    }

    @Test
    void zeroTrueProportionExcludesTheDataPointInsteadOfDividingByZero() {
        // Traced scenario (re-traced after the mix64 seed-derivation fix changed which
        // seed/population combinations produce this population): at this seed/population,
        // the generated population happens to contain zero legal-aid participants, so
        // proportion-legal-aid's true proportion is exactly 0.0. Without the trueProportion
        // == 0.0 guard, the code divides by that zero denominator (Math.abs(observedProportion
        // - 0.0) / 0.0); since the legal-aid group also never appears in the release (it
        // never existed in the true data either), the numerator is also exactly 0.0,
        // producing NaN (0.0 / 0.0) that would corrupt Statistics.percentile's sort. The fix
        // drops that data point instead, so dataPoints() is one lower than the pre-fix count
        // traced for this same scenario (verified against the real, temporarily-reverted
        // production code: 23 without the guard, 22 with it, asserted here).
        var result = new SweepPointRunner(1).run(0.2, 5, 3, false, 0, 0, 0, 0);
        assertThat(result.dataPoints()).isEqualTo(22);
        assertThat(result.medianRelativeError()).isFinite();
        assertThat(result.iqrRelativeError()).isFinite();
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
