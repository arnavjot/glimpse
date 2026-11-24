package com.arnavjot.glimpse;

import static com.google.common.truth.Truth.assertThat;

import com.arnavjot.glimpse.analytics.Percentiles;
import com.arnavjot.glimpse.player.PreloadPolicy;
import com.arnavjot.glimpse.player.PreloadPolicy.Stage;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Plain-JVM tests: no Android framework needed. */
public class PureLogicTest {

    @Test
    public void preloadLoadsTheNextClipAndLessFurtherAway() {
        assertThat(PreloadPolicy.forDistance(1).stage).isEqualTo(Stage.LOAD_FOR_DURATION);
        assertThat(PreloadPolicy.forDistance(1).durationMs).isEqualTo(PreloadPolicy.NEXT_CLIP_MS);
        assertThat(PreloadPolicy.forDistance(2).stage).isEqualTo(Stage.TRACKS_SELECTED);
        assertThat(PreloadPolicy.forDistance(3).stage).isEqualTo(Stage.SOURCE_PREPARED);
        assertThat(PreloadPolicy.forDistance(-1).stage).isEqualTo(Stage.SOURCE_PREPARED);
    }

    @Test
    public void nothingIsPreloadedForTheCurrentOrFarAwayClips() {
        assertThat(PreloadPolicy.forDistance(0).stage).isEqualTo(Stage.NONE);
        assertThat(PreloadPolicy.forDistance(4).stage).isEqualTo(Stage.NONE);
        assertThat(PreloadPolicy.forDistance(-2).stage).isEqualTo(Stage.NONE);
    }

    @Test
    public void percentilesUseNearestRankLikeTheBackend() {
        List<Long> hundred = new ArrayList<>();
        for (long i = 1; i <= 100; i++) hundred.add(i);
        Collections.shuffle(hundred);
        assertThat(Percentiles.of(hundred, 50)).isEqualTo(50L);
        assertThat(Percentiles.of(hundred, 95)).isEqualTo(95L);
        assertThat(Percentiles.of(Arrays.asList(40L, 60L, 80L, 100L), 50)).isEqualTo(60L);
        assertThat(Percentiles.of(Collections.singletonList(7L), 95)).isEqualTo(7L);
        assertThat(Percentiles.of(Collections.emptyList(), 50)).isNull();
    }
}
