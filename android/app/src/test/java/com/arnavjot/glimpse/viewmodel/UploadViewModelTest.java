package com.arnavjot.glimpse.viewmodel;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** Title rules, checked before anything is copied or sent. */
public class UploadViewModelTest {

    @Test
    public void aNormalTitlePasses() {
        assertThat(UploadViewModel.checkTitle("  Sunset at the beach  ")).isNull();
    }

    @Test
    public void emptyAndOverlongTitlesAreCaught() {
        assertThat(UploadViewModel.checkTitle("   ")).contains("title");
        assertThat(UploadViewModel.checkTitle("x".repeat(101))).contains("100");
    }

    @Test
    public void theAcceptedTypesMatchTheServer() {
        assertThat(UploadViewModel.TYPES).containsExactly("video/mp4", "video/quicktime", "video/webm", "video/3gpp");
    }
}
