package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LaplaceNoiseSourceTest {

    @Test
    void isReproducibleFromASeed() {
        var a = new LaplaceNoiseSource(new Random(42)).noise(0.5);
        var b = new LaplaceNoiseSource(new Random(42)).noise(0.5);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentSeedsGiveDifferentNoise() {
        var a = new LaplaceNoiseSource(new Random(1)).noise(0.5);
        var b = new LaplaceNoiseSource(new Random(2)).noise(0.5);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void smallerEpsilonGivesLargerSpread() {
        // scale is 1/epsilon, so a tighter privacy budget must add more noise
        var tight = spread(new LaplaceNoiseSource(new Random(7)), 0.1);
        var loose = spread(new LaplaceNoiseSource(new Random(7)), 2.0);
        assertThat(tight).isGreaterThan(loose);
    }

    @Test
    void isCentredNearZeroOverManyDraws() {
        var source = new LaplaceNoiseSource(new Random(11));
        double total = 0;
        for (var i = 0; i < 20000; i++) {
            total += source.noise(1.0);
        }
        assertThat(Math.abs(total / 20000)).isLessThan(0.1);
    }

    @Test
    void rejectsANonPositiveEpsilon() {
        var source = new LaplaceNoiseSource(new Random(1));
        assertThatThrownBy(() -> source.noise(0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source.noise(-1.0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resamplesTheOneDrawThatWouldProduceAnInfiniteResult() {
        // nextDouble() can legitimately return exactly 0.0, which would make
        // 1 - 2|u| zero and the logarithm infinite. That value must be resampled,
        // because an infinite noise value would poison every downstream sum.
        var first = new java.util.Random() {
            private boolean served;

            @Override
            public double nextDouble() {
                if (!served) {
                    served = true;
                    return 0.0;
                }
                return 0.25;
            }
        };
        var value = new LaplaceNoiseSource(first).noise(1.0);
        assertThat(value).isFinite();
    }

    private double spread(NoiseSource source, double epsilon) {
        double sumSquares = 0;
        for (var i = 0; i < 5000; i++) {
            var n = source.noise(epsilon);
            sumSquares += n * n;
        }
        return Math.sqrt(sumSquares / 5000);
    }
}
