package org.benefits.experiments.g1;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class StatisticsTest {

    @Test
    void medianOfAnOddCountIsTheMiddleValue() {
        assertThat(Statistics.median(List.of(1.0, 3.0, 2.0))).isEqualTo(2.0);
    }

    @Test
    void medianOfAnEvenCountInterpolates() {
        assertThat(Statistics.median(List.of(1.0, 2.0, 3.0, 4.0))).isEqualTo(2.5);
    }

    @Test
    void iqrOfAUniformSpreadMatchesTheKnownValue() {
        var values = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0);
        assertThat(Statistics.iqr(values)).isCloseTo(3.5, within(1e-9));
    }

    @Test
    void percentileOfASingleValueIsThatValue() {
        assertThat(Statistics.percentile(List.of(5.0), 0.9)).isEqualTo(5.0);
    }

    @Test
    void percentileOfAnEmptyListThrows() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Statistics.median(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
