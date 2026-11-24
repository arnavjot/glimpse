package com.arnavjot.glimpse.server.uploads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The real ffmpeg pipeline (skipped where ffmpeg isn't installed; CI installs it). */
class FfmpegTranscoderTest {

    @TempDir Path dir;

    @BeforeAll
    static void needsFfmpeg() {
        assumeTrue(available("ffmpeg") && available("ffprobe"), "ffmpeg is not installed");
    }

    private static boolean available(String tool) {
        try {
            Process p = new ProcessBuilder(tool, "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A landscape 1280x720 test clip, optionally with a tone. */
    private Path sample(String name, double seconds, boolean audio) throws Exception {
        Path out = dir.resolve(name);
        List<String> cmd = new java.util.ArrayList<>(List.of("ffmpeg", "-nostdin", "-v", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30:duration=" + seconds));
        if (audio) cmd.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:duration=" + seconds));
        cmd.addAll(List.of("-c:v", "libx264", "-pix_fmt", "yuv420p"));
        if (audio) cmd.addAll(List.of("-c:a", "aac", "-shortest"));
        cmd.add(out.toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).isZero();
        return out;
    }

    @Test
    void producesThreeQualitiesWithAMasterPlaylistAndAPoster() throws Exception {
        Path out = Files.createDirectory(dir.resolve("out"));
        long ms = new FfmpegTranscoder().transcode(sample("in.mp4", 5, true), out, 60);
        assertThat(ms).isBetween(4900L, 5100L);

        String master = Files.readString(out.resolve("index.m3u8"));
        assertThat(master.split("#EXT-X-STREAM-INF", -1)).hasSize(4); // 3 variants
        assertThat(master).contains("720p.m3u8", "540p.m3u8", "360p.m3u8")
                .contains("RESOLUTION=720x1280", "RESOLUTION=540x960", "RESOLUTION=360x640");
        assertThat(Files.readString(out.resolve("720p.m3u8"))).contains("#EXT-X-ENDLIST");
        assertThat(out.resolve("poster.jpg")).isRegularFile();
        try (var files = Files.list(out)) {
            assertThat(files.filter(f -> f.toString().endsWith(".ts")).count()).isGreaterThanOrEqualTo(9); // ~3 x 3
        }
    }

    @Test
    void longClipsAreCutAndSilentOnesWork() throws Exception {
        Path out = Files.createDirectory(dir.resolve("out"));
        long ms = new FfmpegTranscoder().transcode(sample("long.mp4", 6, false), out, 3);
        assertThat(ms).isEqualTo(3000);
        assertThat(Files.readString(out.resolve("index.m3u8"))).contains("360p.m3u8");
    }

    @Test
    void aCommandThatHangsIsKilledAtTheTimeLimit() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> FfmpegTranscoder.run(List.of("sleep", "30"), 500))
                .isInstanceOf(Transcoder.TranscodeException.class)
                .hasMessageContaining("took too long");
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(10_000);
    }

    @Test
    void somethingThatIsNotAVideoIsRejected() throws Exception {
        Path notVideo = Files.writeString(dir.resolve("notes.mp4"), "hello");
        Path out = Files.createDirectory(dir.resolve("out"));
        assertThatThrownBy(() -> new FfmpegTranscoder().transcode(notVideo, out, 60))
                .isInstanceOf(Transcoder.TranscodeException.class);
    }
}
