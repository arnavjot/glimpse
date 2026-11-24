package com.arnavjot.glimpse.benchmark;

import androidx.benchmark.macro.BaselineProfileMode;
import androidx.benchmark.macro.CompilationMode;
import androidx.benchmark.macro.FrameTimingMetric;
import androidx.benchmark.macro.StartupMode;
import androidx.benchmark.macro.junit4.MacrobenchmarkRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

/**
 * Frame timing while swiping through the feed: frameDurationCpuMs and
 * frameOverrunMs percentiles tell you how smooth swiping is (jank). Needs the
 * backend running and reachable from the phone.
 */
@RunWith(AndroidJUnit4.class)
public class FeedScrollBenchmark {

    @Rule public MacrobenchmarkRule rule = new MacrobenchmarkRule();

    @Test
    public void swipeTenClips() {
        rule.measureRepeated(
                Journeys.PACKAGE,
                Collections.singletonList(new FrameTimingMetric()),
                new CompilationMode.Partial(BaselineProfileMode.Require),
                StartupMode.WARM,
                5,
                scope -> {
                    scope.startActivityAndWait();
                    Journeys.waitForFeed(scope);
                    return null;
                },
                scope -> {
                    Journeys.swipeFeed(scope, 10);
                    return null;
                });
    }
}
