package com.arnavjot.glimpse.benchmark;

import androidx.benchmark.macro.BaselineProfileMode;
import androidx.benchmark.macro.CompilationMode;
import androidx.benchmark.macro.StartupMode;
import androidx.benchmark.macro.StartupTimingMetric;
import androidx.benchmark.macro.junit4.MacrobenchmarkRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

/**
 * Cold start time, without and with the Baseline Profile. Run on a physical
 * phone (emulator timings aren't meaningful):
 *
 * <pre>./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest</pre>
 *
 * The difference between the two tests is what the Baseline Profile buys.
 */
@RunWith(AndroidJUnit4.class)
public class StartupBenchmark {

    @Rule public MacrobenchmarkRule rule = new MacrobenchmarkRule();

    @Test
    public void coldStartWithoutBaselineProfile() {
        measure(new CompilationMode.None());
    }

    @Test
    public void coldStartWithBaselineProfile() {
        measure(new CompilationMode.Partial(BaselineProfileMode.Require));
    }

    private void measure(CompilationMode mode) {
        rule.measureRepeated(
                Journeys.PACKAGE,
                Collections.singletonList(new StartupTimingMetric()),
                mode,
                StartupMode.COLD,
                10,
                scope -> {
                    scope.pressHome();
                    scope.startActivityAndWait();
                    return null;
                });
    }
}
