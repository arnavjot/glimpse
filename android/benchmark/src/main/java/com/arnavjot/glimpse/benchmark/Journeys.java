package com.arnavjot.glimpse.benchmark;

import androidx.benchmark.macro.MacrobenchmarkScope;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

/** User journeys shared by the benchmarks and the Baseline Profile generator. */
final class Journeys {

    static final String PACKAGE = "com.arnavjot.glimpse";

    private Journeys() {}

    /** Wait until the feed pager is on screen. */
    static UiObject2 waitForFeed(MacrobenchmarkScope scope) {
        UiDevice device = scope.getDevice();
        device.wait(Until.hasObject(By.res(PACKAGE, "pager")), 10_000);
        return device.findObject(By.res(PACKAGE, "pager"));
    }

    /** Swipe up through {@code clips} clips, pausing as a viewer would. */
    static void swipeFeed(MacrobenchmarkScope scope, int clips) {
        UiObject2 pager = waitForFeed(scope);
        if (pager == null) return;
        // Leave a margin so the swipe doesn't start on the system gesture area.
        pager.setGestureMargin(scope.getDevice().getDisplayWidth() / 5);
        for (int i = 0; i < clips; i++) {
            pager.fling(Direction.DOWN); // content moves up: the next clip
            scope.getDevice().waitForIdle(1_000);
        }
    }
}
