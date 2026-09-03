package org.benefits.experiments.e4;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatisticsTest {

    @Test
    void medianOfAnOddCountIsTheMiddleValue() {
        assertThat(Statistics.median(List.of(1.0, 3.0, 2.0))).isEqualTo(2.0);
    }

    @Test
    void medianOfAnEvenCountAveragesTheTwoMiddleValues() {
        assertThat(Statistics.median(List.of(1.0, 2.0, 3.0, 4.0))).isEqualTo(2.5);
    }

    @Test
    void medianOfASingleValueIsThatValue() {
        assertThat(Statistics.median(List.of(7.0))).isEqualTo(7.0);
    }

    @Test
    void iqrOfAUniformZeroToOneHundredSampleIsFifty() {
        List<Double> values = new java.util.ArrayList<>();
        for (var i = 0; i <= 100; i++) {
            values.add((double) i);
        }
        assertThat(Statistics.iqr(values)).isCloseTo(50.0, within(0.5));
    }

    @Test
    void percentileRejectsAnEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> Statistics.median(List.of()));
    }

    @Test
    void medianIsUnaffectedByInputOrder() {
        var sorted = Statistics.median(List.of(1.0, 2.0, 3.0, 4.0, 5.0));
        var shuffled = Statistics.median(List.of(5.0, 1.0, 4.0, 2.0, 3.0));
        assertThat(sorted).isEqualTo(shuffled);
    }
}
