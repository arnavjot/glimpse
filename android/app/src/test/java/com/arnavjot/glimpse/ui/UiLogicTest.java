package com.arnavjot.glimpse.ui;

import static com.google.common.truth.Truth.assertThat;

import com.arnavjot.glimpse.common.Event;

import org.junit.Test;

/** Plain JVM tests for the small pure functions behind the UI. */
public class UiLogicTest {

    @Test
    public void avatarInitialIsTheUpperCasedFirstLetter() {
        assertThat(FeedOverlay.initial("pixelfield")).isEqualTo("P");
        assertThat(FeedOverlay.initial("ärger")).isEqualTo("Ä"); // ärger -> Ä
    }

    @Test
    public void avatarInitialKeepsCharactersOutsideTheBasicPlane() {
        String emoji = new String(Character.toChars(0x1F3AC)); // a 2-char surrogate pair
        assertThat(FeedOverlay.initial(emoji + "studio")).isEqualTo(emoji);
    }

    @Test
    public void avatarInitialOfNothingIsEmpty() {
        assertThat(FeedOverlay.initial(null)).isEmpty();
        assertThat(FeedOverlay.initial("")).isEmpty();
    }

    @Test
    public void apiUrlMustBeAnAbsoluteHttpUrl() {
        assertThat(SettingsActivity.isValidApiUrl("http://192.168.1.40:8000/")).isTrue();
        assertThat(SettingsActivity.isValidApiUrl(" https://api.example.com ")).isTrue();
        assertThat(SettingsActivity.isValidApiUrl("192.168.1.40:8000")).isFalse();
        assertThat(SettingsActivity.isValidApiUrl("ftp://host/")).isFalse();
        assertThat(SettingsActivity.isValidApiUrl("")).isFalse();
        assertThat(SettingsActivity.isValidApiUrl(null)).isFalse();
    }

    @Test
    public void anEventIsConsumedOnce() {
        Event<String> event = new Event<>("x");
        assertThat(event.consume()).isEqualTo("x");
        assertThat(event.consume()).isNull();
        assertThat(event.peek()).isEqualTo("x");
    }
}
