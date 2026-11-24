package com.arnavjot.glimpse.server.experiments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import org.junit.jupiter.api.Test;

/** The statistics behind the experiment report, against textbook values. */
class ExperimentReportTest {

    @Test
    void pValuesMatchTheNormalTable() {
        assertThat(ExperimentReport.twoSidedP(0)).isCloseTo(1.0, offset(1e-6));
        assertThat(ExperimentReport.twoSidedP(1.96)).isCloseTo(0.05, offset(1e-3));
        assertThat(ExperimentReport.twoSidedP(-2.576)).isCloseTo(0.01, offset(1e-3));
    }

    @Test
    void aClearDifferenceInProportionsIsSignificant() {
        // 40% vs 50% completion over 1000 plays each: z = 4.5
        ExperimentReport.Comparison c = ExperimentReport.compareProportions(400, 1000, 500, 1000);
        assertThat(c.significant()).isTrue();
        assertThat(c.lift()).isEqualTo(0.25);
    }

    @Test
    void aSmallDifferenceOnFewPlaysIsNot() {
        ExperimentReport.Comparison c = ExperimentReport.compareProportions(4, 10, 5, 10);
        assertThat(c.significant()).isFalse();
        assertThat(c.pValue()).isGreaterThan(0.5);
    }

    @Test
    void meansNeedEnoughSpreadToBeTold() {
        assertThat(ExperimentReport.compareMeans(100, 2500, 50, 110, 2500, 50).significant()).isFalse(); // z = 1
        assertThat(ExperimentReport.compareMeans(100, 2500, 500, 110, 2500, 500).significant()).isTrue(); // z = 3.2
        assertThat(ExperimentReport.compareMeans(100, 0, 1, 200, 0, 1).pValue()).isNull(); // too few devices
    }
}
