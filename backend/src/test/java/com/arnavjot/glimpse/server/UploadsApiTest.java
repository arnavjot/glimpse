package com.arnavjot.glimpse.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.arnavjot.glimpse.server.uploads.Transcoder;
import com.arnavjot.glimpse.server.uploads.UploadCleaner;
import com.arnavjot.glimpse.server.uploads.UploadWorker;

/**
 * Uploads end to end against real Postgres and S3-compatible storage: pre-signed PUT,
 * completion, and the worker's state machine. ffmpeg is replaced by a mock here
 * (FfmpegTranscoderTest covers the real thing).
 */
class UploadsApiTest extends IntegrationTest {

    private static final byte[] FILE = "not really a video, the transcoder is mocked".getBytes();

    @MockitoBean Transcoder transcoder;
    @Autowired UploadWorker worker;
    @Autowired UploadCleaner cleaner;

    private final HttpClient s3Client = HttpClient.newHttpClient();

    private String signUp(String handle) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> body = http.postForObject("/v1/auth/register", new HttpEntity<>(
                "{\"email\":\"" + handle + "@example.com\",\"password\":\"correct horse\",\"handle\":\"" + handle + "\"}",
                h), Map.class);
        return (String) body.get("access_token");
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        return h;
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> create(String token, String type, long size) {
        return http.postForEntity("/v1/uploads", new HttpEntity<>(
                "{\"title\":\"My clip\",\"content_type\":\"" + type + "\",\"size_bytes\":" + size + "}", auth(token)),
                Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> upload(Map<?, ?> ticket) {
        return (Map<String, Object>) ticket.get("upload");
    }

    private int put(String url, String type, byte[] bytes) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", type)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        return s3Client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> complete(String token, Object id) {
        ResponseEntity<Map> r = http.postForEntity("/v1/uploads/" + id + "/complete", new HttpEntity<>(auth(token)),
                Map.class);
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> status(String token, Object id) {
        return http.exchange("/v1/uploads/" + id, HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class).getBody();
    }

    /** Signs up, creates an upload, PUTs the file to storage and completes it. @return upload id */
    private Object uploaded(String token) throws Exception {
        Map<?, ?> ticket = create(token, "video/mp4", FILE.length).getBody();
        assertThat(put((String) ticket.get("upload_url"), "video/mp4", FILE)).isEqualTo(200);
        Object id = upload(ticket).get("id");
        assertThat(complete(token, id)).containsEntry("status", "uploaded");
        return id;
    }

    private void transcoderWritesHls() throws Exception {
        doAnswer(inv -> {
            Path out = inv.getArgument(1);
            Files.writeString(out.resolve("index.m3u8"), "#EXTM3U\n");
            Files.writeString(out.resolve("poster.jpg"), "jpg");
            return 5000L;
        }).when(transcoder).transcode(any(), any(), anyInt());
    }

    @Test
    void uploadingNeedsAnAccount() {
        assertThat(create(null, "video/mp4", 10).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theFileGoesStraightToStorageWithAPresignedUrl() throws Exception {
        String token = signUp("maker");
        ResponseEntity<Map> created = create(token, "video/mp4", FILE.length);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> ticket = created.getBody();
        assertThat((String) ticket.get("upload_url")).startsWith(s3Endpoint()).contains("X-Amz-Signature");
        assertThat(upload(ticket)).containsEntry("status", "pending_upload");

        Object id = uploaded(token);
        assertThat(complete(token, id)).containsEntry("status", "uploaded"); // completing twice is fine
    }

    @Test
    void completingBeforeTheFileArrivedIsRefused() {
        String token = signUp("maker");
        Object id = upload(create(token, "video/mp4", FILE.length).getBody()).get("id");
        ResponseEntity<String> r = http.postForEntity("/v1/uploads/" + id + "/complete",
                new HttpEntity<>(auth(token)), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void storageRefusesAFileThatIsNotTheDeclaredSize() throws Exception {
        String token = signUp("maker");
        Map<?, ?> ticket = create(token, "video/mp4", 999).getBody(); // declared 999 bytes
        // The size is part of the URL's signature: any other size is refused at upload time.
        assertThat(put((String) ticket.get("upload_url"), "video/mp4", FILE)).isEqualTo(403);
        ResponseEntity<String> r = http.postForEntity("/v1/uploads/" + upload(ticket).get("id") + "/complete",
                new HttpEntity<>(auth(token)), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // nothing arrived
    }

    @Test
    void unsupportedTypesAndHugeFilesAreRejectedUpFront() {
        String token = signUp("maker");
        assertThat(create(token, "image/png", 10).getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(create(token, "video/mp4", 500L * 1024 * 1024).getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void anotherCreatorsUploadIsInvisible() throws Exception {
        Object id = uploaded(signUp("maker"));
        ResponseEntity<String> r = http.exchange("/v1/uploads/" + id, HttpMethod.GET,
                new HttpEntity<>(auth(signUp("other"))), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theKillSwitchPausesUploads() {
        String token = signUp("maker");
        jdbc.update("UPDATE remote_config SET enabled = FALSE WHERE key = 'uploads'");
        remoteConfig.invalidate();
        assertThat(create(token, "video/mp4", 10).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aProcessedUploadIsPublishedAtTheTopOfTheFeed() throws Exception {
        seedVideos(3);
        String token = signUp("maker");
        Object id = uploaded(token);
        transcoderWritesHls();

        assertThat(worker.runOnce()).isTrue();
        Map<String, Object> done = status(token, id);
        assertThat(done).containsEntry("status", "ready");
        String videoId = (String) done.get("video_id");
        assertThat(Files.exists(MEDIA_DIR.resolve(videoId).resolve("index.m3u8"))).isTrue();

        Map<?, ?> feed = http.getForObject("/v1/feed?limit=10", Map.class);
        Map<String, Object> first = ((List<Map<String, Object>>) feed.get("items")).get(0);
        assertThat(first).containsEntry("id", videoId).containsEntry("creator", "maker")
                .containsEntry("title", "My clip").containsEntry("duration_ms", 5000);
        assertThat(worker.runOnce()).isFalse(); // queue empty
    }

    @Test
    void aVideoThatCannotBeProcessedFailsWithAReason() throws Exception {
        String token = signUp("maker");
        Object id = uploaded(token);
        doThrow(new Transcoder.TranscodeException("that file has no video", "no video stream"))
                .when(transcoder).transcode(any(), any(), anyInt());
        worker.runOnce();
        assertThat(status(token, id)).containsEntry("status", "failed").containsEntry("error", "that file has no video");
    }

    @Test
    void aTemporaryErrorIsRetried() throws Exception {
        String token = signUp("maker");
        Object id = uploaded(token);
        doThrow(new IllegalStateException("disk full")).when(transcoder).transcode(any(), any(), anyInt());
        worker.runOnce();
        assertThat(status(token, id)).containsEntry("status", "uploaded"); // back in the queue
        transcoderWritesHls();
        worker.runOnce();
        assertThat(status(token, id)).containsEntry("status", "ready");
    }

    @Test
    void aJobLeftByACrashedWorkerIsTakenOver() throws Exception {
        String token = signUp("maker");
        Object id = uploaded(token);
        // A worker claimed it, then died: still "processing", lease already expired.
        jdbc.update("UPDATE uploads SET status = 'processing', attempts = 1, lease_until = now() - interval '1 minute' "
                + "WHERE id = ?::uuid", id.toString());
        transcoderWritesHls();
        assertThat(worker.runOnce()).isTrue();
        assertThat(status(token, id)).containsEntry("status", "ready");
    }

    @Test
    void aJobUnderAnotherWorkersLeaseIsLeftAlone() throws Exception {
        String token = signUp("maker");
        Object id = uploaded(token);
        jdbc.update("UPDATE uploads SET status = 'processing', attempts = 1, lease_until = now() + interval '5 minutes' "
                + "WHERE id = ?::uuid", id.toString());
        assertThat(worker.runOnce()).isFalse();
        assertThat(status(token, id)).containsEntry("status", "processing");
    }

    @Test
    void aMalformedUploadIdIsA400() {
        ResponseEntity<String> r = http.exchange("/v1/uploads/not-a-uuid", HttpMethod.GET,
                new HttpEntity<>(auth(signUp("maker"))), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // Deleting and cleaning up

    private ResponseEntity<String> delete(String token, Object id) {
        return http.exchange("/v1/uploads/" + id, HttpMethod.DELETE, new HttpEntity<>(auth(token)), String.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletingALiveClipTakesItOutOfTheFeedAtOnceAndTheFilesSoonAfter() throws Exception {
        seedVideos(2);
        String token = signUp("maker");
        Object id = uploaded(token);
        transcoderWritesHls();
        worker.runOnce();
        String videoId = (String) status(token, id).get("video_id");
        assertThat(Files.exists(MEDIA_DIR.resolve(videoId))).isTrue();

        assertThat(delete(token, id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Map<?, ?> feed = http.getForObject("/v1/feed?limit=10", Map.class);
        assertThat(((List<Map<String, Object>>) feed.get("items")).stream().map(v -> v.get("id"))).doesNotContain(videoId);
        assertThat(http.getForEntity("/v1/videos/" + videoId, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.exchange("/v1/uploads", HttpMethod.GET, new HttpEntity<>(auth(token)), List.class).getBody())
                .isEmpty();
        assertThat(delete(token, id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT); // twice is fine

        assertThat(cleaner.runOnce()).isEqualTo(1);
        assertThat(Files.exists(MEDIA_DIR.resolve(videoId))).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM uploads", Integer.class)).isZero();
    }

    @Test
    void someoneElsesUploadCannotBeDeleted() throws Exception {
        Object id = uploaded(signUp("maker"));
        assertThat(delete(signUp("other"), id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT status FROM uploads", String.class)).isEqualTo("uploaded");
    }

    @Test
    void aClipDeletedWhileProcessingIsNeverPublished() throws Exception {
        String token = signUp("maker");
        Object id = uploaded(token);
        doAnswer(inv -> {
            delete(token, id); // the creator deletes it while ffmpeg runs
            Path out = inv.getArgument(1);
            Files.writeString(out.resolve("index.m3u8"), "#EXTM3U\n");
            return 5000L;
        }).when(transcoder).transcode(any(), any(), anyInt());
        worker.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM videos", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM uploads", String.class)).isEqualTo("deleted");
        // The worker never moved its output into place: no files left for anyone to clean up.
        assertThat(Files.exists(MEDIA_DIR.resolve(UploadWorker.videoId(java.util.UUID.fromString(id.toString())))))
                .isFalse();
        assertNoStagingLeft();
    }

    @Test
    void aWorkerWhoseJobWasTakenOverDropsItsOutput() throws Exception {
        String token = signUp("maker");
        Object id = uploaded(token);
        doAnswer(inv -> {
            // Our lease ran out mid-transcode and another worker claimed the job (attempt 2).
            jdbc.update("UPDATE uploads SET attempts = attempts + 1 WHERE id = ?::uuid", id.toString());
            Path out = inv.getArgument(1);
            Files.writeString(out.resolve("index.m3u8"), "#EXTM3U\n");
            return 5000L;
        }).when(transcoder).transcode(any(), any(), anyInt());
        worker.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM videos", Integer.class)).isZero();
        assertThat(Files.exists(MEDIA_DIR.resolve(UploadWorker.videoId(java.util.UUID.fromString(id.toString())))))
                .isFalse(); // the new owner publishes, not us
        assertNoStagingLeft();
    }

    private static void assertNoStagingLeft() throws Exception {
        try (var files = Files.list(MEDIA_DIR)) {
            assertThat(files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(".staging"))).isEmpty();
        }
    }

    @Test
    void abandonedAndOldFailedUploadsAreCleanedUpButRecentOnesStay() throws Exception {
        String token = signUp("maker");
        Object fresh = upload(create(token, "video/mp4", 10).getBody()).get("id");     // just started
        Object abandoned = upload(create(token, "video/mp4", 10).getBody()).get("id");
        Object oldFailed = upload(create(token, "video/mp4", 10).getBody()).get("id");
        jdbc.update("UPDATE uploads SET updated_at = now() - interval '2 days' WHERE id = ?::uuid", abandoned.toString());
        jdbc.update("UPDATE uploads SET status = 'failed', updated_at = now() - interval '8 days' WHERE id = ?::uuid",
                oldFailed.toString());

        assertThat(cleaner.runOnce()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT id::text FROM uploads", String.class)).containsExactly(fresh.toString());
    }
}
