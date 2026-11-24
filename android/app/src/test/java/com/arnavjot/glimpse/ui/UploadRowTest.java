package com.arnavjot.glimpse.ui;

import static com.google.common.truth.Truth.assertThat;

import com.arnavjot.glimpse.pojo.UploadDto;
import com.arnavjot.glimpse.viewmodel.MyUploadsViewModel;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** How server statuses show on My uploads, and when the screen keeps polling. */
public class UploadRowTest {

    private static UploadDto.Upload upload(String status, String videoId, String error) {
        UploadDto.Upload u = new UploadDto.Upload();
        u.id = "id-" + status;
        u.title = "Clip";
        u.status = status;
        u.videoId = videoId;
        u.error = error;
        return u;
    }

    @Test
    public void aReadyUploadLinksToItsClip() {
        UploadRow r = UploadRow.of(upload(UploadDto.READY, "u123", null));
        assertThat(r.kind).isEqualTo(UploadRow.Kind.READY);
        assertThat(r.videoId).isEqualTo("u123");
    }

    @Test
    public void onlyAFailedUploadShowsItsError() {
        assertThat(UploadRow.of(upload(UploadDto.FAILED, null, "that file has no video")).error)
                .isEqualTo("that file has no video");
        assertThat(UploadRow.of(upload(UploadDto.UPLOADED, null, "temporary problem, retrying")).error).isNull();
    }

    @Test
    public void localAndServerRowsAreToldApart() {
        UploadRow local = new UploadRow(UploadRow.LOCAL + "1b4e28ba-2fa1-11d2-883f-0016d3cca427", "Clip",
                UploadRow.Kind.SENDING, 40, null, null);
        assertThat(local.isLocal()).isTrue();
        assertThat(local.id()).isEqualTo("1b4e28ba-2fa1-11d2-883f-0016d3cca427");
        UploadRow server = UploadRow.of(upload(UploadDto.READY, "u1", null));
        assertThat(server.isLocal()).isFalse();
        assertThat(server.id()).isEqualTo("id-ready");
    }

    @Test
    public void anUnknownStatusIsTreatedAsFailed() {
        assertThat(UploadRow.of(upload("something_new", null, null)).kind).isEqualTo(UploadRow.Kind.FAILED);
    }

    @Test
    public void pollingContinuesOnlyWhileTheServerIsWorking() {
        assertThat(MyUploadsViewModel.anyInProgress(Arrays.asList(
                upload(UploadDto.READY, "u1", null), upload(UploadDto.PROCESSING, null, null)))).isTrue();
        assertThat(MyUploadsViewModel.anyInProgress(Arrays.asList(
                upload(UploadDto.READY, "u1", null), upload(UploadDto.FAILED, null, "x")))).isFalse();
        // An abandoned pending upload must not keep the screen polling forever.
        assertThat(MyUploadsViewModel.anyInProgress(Collections.singletonList(
                upload(UploadDto.PENDING, null, null)))).isFalse();
        assertThat(MyUploadsViewModel.anyInProgress(null)).isFalse();
    }
}
