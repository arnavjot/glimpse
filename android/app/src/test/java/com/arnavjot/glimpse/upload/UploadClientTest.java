package com.arnavjot.glimpse.upload;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.arnavjot.glimpse.TestSupport;
import com.arnavjot.glimpse.pojo.UploadDto;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** The three-step upload, retries that resume, and which failures are permanent. */
public class UploadClientTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** In-memory stand-in for the per-job SharedPreferences. */
    private static final class MemoryState implements UploadClient.JobState {
        String id;
        String url;
        long expires;

        @Override public String uploadId() { return id; }
        @Override public String uploadUrl() { return url; }
        @Override public long urlExpiresAtMillis() { return expires; }

        @Override
        public void save(String uploadId, String uploadUrl, long urlExpiresAtMillis) {
            id = uploadId;
            url = uploadUrl;
            expires = urlExpiresAtMillis;
        }

        @Override
        public void clearUrl() {
            url = null;
            expires = 0;
        }
    }

    private MockWebServer server;
    private UploadClient client;
    private final MemoryState state = new MemoryState();
    private long now = 1_000_000;
    private File video;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new UploadClient(TestSupport.api(server),
                new OkHttpClient.Builder().retryOnConnectionFailure(false).build(), () -> now);
        video = tmp.newFile("clip.mp4");
        Files.write(video.toPath(), new byte[300 * 1024]); // 300 KB: several progress chunks
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private String ticket(String id) {
        return "{\"upload\":{\"id\":\"" + id + "\",\"status\":\"pending_upload\"},"
                + "\"upload_url\":\"" + server.url("/storage/" + id + "?X-Amz-Signature=abc") + "\","
                + "\"content_type\":\"video/mp4\"}";
    }

    private static String upload(String id, String status) {
        return "{\"id\":\"" + id + "\",\"title\":\"Clip\",\"status\":\"" + status + "\"}";
    }

    private UploadDto.Upload run() throws Exception {
        return client.upload(video, "Clip", "video/mp4", state, (sent, total) -> { });
    }

    @Test
    public void createThenPutStraightToStorageThenComplete() throws Exception {
        server.enqueue(new MockResponse().setBody(ticket("u1")));
        server.enqueue(new MockResponse());                            // PUT to storage
        server.enqueue(new MockResponse().setBody(upload("u1", "uploaded")));
        List<long[]> progress = new ArrayList<>();
        UploadDto.Upload done = client.upload(video, "Clip", "video/mp4", state,
                (sent, total) -> progress.add(new long[] {sent, total}));

        assertThat(done.status).isEqualTo("uploaded");
        RecordedRequest create = server.takeRequest();
        assertThat(create.getPath()).isEqualTo("/v1/uploads");
        assertThat(create.getBody().readUtf8()).contains("\"size_bytes\":" + video.length());
        RecordedRequest put = server.takeRequest();
        assertThat(put.getMethod()).isEqualTo("PUT");
        assertThat(put.getPath()).startsWith("/storage/u1");
        assertThat(put.getHeader("Content-Type")).startsWith("video/mp4");
        assertThat(put.getBodySize()).isEqualTo(video.length());
        assertThat(server.takeRequest().getPath()).isEqualTo("/v1/uploads/u1/complete");
        assertThat(progress.get(progress.size() - 1)).isEqualTo(new long[] {video.length(), video.length()});
        assertThat(progress.size()).isGreaterThan(1);
    }

    @Test
    public void aRetryContinuesTheSameUpload() throws Exception {
        server.enqueue(new MockResponse().setBody(ticket("u1")));
        server.enqueue(new MockResponse().setResponseCode(503));       // storage hiccup
        assertThrows(IOException.class, this::run);
        assertThat(state.id).isEqualTo("u1");

        server.enqueue(new MockResponse());                            // PUT again, same URL
        server.enqueue(new MockResponse().setBody(upload("u1", "uploaded")));
        run();
        assertThat(server.getRequestCount()).isEqualTo(4); // one create in total, not two
    }

    @Test
    public void aRefusedOrExpiredUrlIsReplacedOnTheNextTry() throws Exception {
        server.enqueue(new MockResponse().setBody(ticket("u1")));
        server.enqueue(new MockResponse().setResponseCode(403));
        assertThrows(IOException.class, this::run);
        assertThat(state.url).isNull();

        server.enqueue(new MockResponse().setBody(ticket("u2")));
        server.enqueue(new MockResponse());
        server.enqueue(new MockResponse().setBody(upload("u2", "uploaded")));
        assertThat(run().id).isEqualTo("u2");
    }

    @Test
    public void anOldUrlIsNotUsed() throws Exception {
        state.save("u1", server.url("/storage/u1").toString(), now - 1); // already past its use-by time
        server.enqueue(new MockResponse().setBody(ticket("u2")));
        server.enqueue(new MockResponse());
        server.enqueue(new MockResponse().setBody(upload("u2", "uploaded")));
        run();
        assertThat(server.takeRequest().getPath()).isEqualTo("/v1/uploads"); // asked for a new one
    }

    @Test
    public void aRejectedFileFailsForGoodWithTheServersReason() {
        server.enqueue(new MockResponse().setResponseCode(415)
                .setBody("{\"detail\":\"that file type isn't supported\"}"));
        UploadClient.PermanentFailure e = assertThrows(UploadClient.PermanentFailure.class, this::run);
        assertThat(e.getMessage()).isEqualTo("That file type isn't supported.");
    }

    @Test
    public void pausedUploadsAreTemporary() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"detail\":\"uploads are paused\"}"));
        assertThrows(IOException.class, this::run); // kill switch on: retry later
    }

    @Test
    public void completingBeforeStorageHasTheFileIsRetried() {
        server.enqueue(new MockResponse().setBody(ticket("u1")));
        server.enqueue(new MockResponse());
        server.enqueue(new MockResponse().setResponseCode(409));
        assertThrows(IOException.class, this::run);
    }
}
