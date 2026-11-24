package com.arnavjot.glimpse.benchmark;

import androidx.benchmark.macro.junit4.BaselineProfileRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Records which code runs during startup and the first swipes, so it can be
 * compiled ahead of time on install. Generate and copy it into the app with:
 *
 * <pre>./gradlew :app:generateBaselineProfile</pre>
 */
@RunWith(AndroidJUnit4.class)
public class BaselineProfileGenerator {

    @Rule public BaselineProfileRule rule = new BaselineProfileRule();

    @Test
    public void generate() {
        rule.collect(Journeys.PACKAGE, scope -> {
            scope.pressHome();
            scope.startActivityAndWait();
            Journeys.swipeFeed(scope, 5);
            return null;
        });
    }
}
