package com.arnavjot.glimpse.server.uploads;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * ffmpeg, three qualities in one pass. The source is scaled to cover 9:16 and centre-cropped
 * once, then split into 720p / 540p / 360p renditions with aligned 2-second keyframes, so
 * the player can switch quality at any segment boundary (adaptive bitrate). The master
 * playlist index.m3u8 lists all three with their bandwidths.
 */
@Component
public class FfmpegTranscoder implements Transcoder {

    record Rendition(String name, int width, int height, int kbps) {}

    static final List<Rendition> RENDITIONS = List.of(
            new Rendition("720p", 720, 1280, 2800),
            new Rendition("540p", 540, 960, 1500),
            new Rendition("360p", 360, 640, 800));

    private static final long TIMEOUT_MINUTES = 10;

    @Override
    public long transcode(Path original, Path outDir, int maxSeconds) throws TranscodeException {
        Probe probe = probe(original);
        double seconds = Math.min(probe.seconds, maxSeconds);
        if (seconds < 1) throw new TranscodeException("that video is too short", "duration " + probe.seconds);

        run(command(original, outDir, seconds, probe.hasAudio));
        run(List.of("ffmpeg", "-nostdin", "-v", "error", "-y", "-ss", String.valueOf(Math.min(1.0, seconds / 2)),
                "-i", original.toString(), "-frames:v", "1", "-q:v", "3",
                "-vf", "scale=720:1280:force_original_aspect_ratio=increase,crop=720:1280",
                outDir.resolve("poster.jpg").toString()));
        return Math.round(seconds * 1000);
    }

    /** The full ffmpeg command line (package-private for tests). */
    static List<String> command(Path original, Path outDir, double seconds, boolean audio) {
        List<String> c = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-v", "error", "-y",
                "-i", original.toString(), "-t", String.format(Locale.ROOT, "%.3f", seconds)));
        // Cover-crop to 9:16 once, then one scaled copy per rendition.
        StringBuilder graph = new StringBuilder(
                "[0:v]scale=720:1280:force_original_aspect_ratio=increase,crop=720:1280,setsar=1,fps=30,split=3");
        for (int i = 0; i < RENDITIONS.size(); i++) graph.append("[s").append(i).append(']');
        for (int i = 0; i < RENDITIONS.size(); i++) {
            Rendition r = RENDITIONS.get(i);
            graph.append(';').append("[s").append(i).append("]scale=").append(r.width()).append(':')
                    .append(r.height()).append("[v").append(i).append(']');
        }
        c.addAll(List.of("-filter_complex", graph.toString()));
        StringBuilder streamMap = new StringBuilder();
        for (int i = 0; i < RENDITIONS.size(); i++) {
            Rendition r = RENDITIONS.get(i);
            c.addAll(List.of("-map", "[v" + i + "]"));
            if (audio) c.addAll(List.of("-map", "0:a:0"));
            c.addAll(List.of("-b:v:" + i, r.kbps() + "k", "-maxrate:v:" + i, (r.kbps() * 11 / 10) + "k",
                    "-bufsize:v:" + i, (r.kbps() * 2) + "k"));
            if (streamMap.length() > 0) streamMap.append(' ');
            streamMap.append("v:").append(i).append(audio ? ",a:" + i : "").append(",name:").append(r.name());
        }
        // Keyframe every 2 s (60 frames) in every rendition: segments line up for switching.
        c.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-profile:v", "main", "-pix_fmt", "yuv420p",
                "-g", "60", "-keyint_min", "60", "-sc_threshold", "0"));
        if (audio) c.addAll(List.of("-c:a", "aac", "-b:a", "128k", "-ac", "2"));
        c.addAll(List.of("-f", "hls", "-hls_time", "2", "-hls_playlist_type", "vod",
                "-hls_segment_filename", outDir.resolve("%v_%03d.ts").toString(),
                "-master_pl_name", "index.m3u8",
                "-var_stream_map", streamMap.toString(),
                outDir.resolve("%v.m3u8").toString()));
        return c;
    }

    private record Probe(double seconds, boolean hasAudio) {}

    private static Probe probe(Path file) throws TranscodeException {
        String duration = run(List.of("ffprobe", "-v", "error", "-show_entries", "format=duration",
                "-of", "csv=p=0", file.toString())).trim();
        String audio = run(List.of("ffprobe", "-v", "error", "-select_streams", "a", "-show_entries",
                "stream=index", "-of", "csv=p=0", file.toString())).trim();
        String video = run(List.of("ffprobe", "-v", "error", "-select_streams", "v", "-show_entries",
                "stream=index", "-of", "csv=p=0", file.toString())).trim();
        if (video.isEmpty()) throw new TranscodeException("that file has no video", "no video stream");
        try {
            return new Probe(Double.parseDouble(duration), !audio.isEmpty());
        } catch (NumberFormatException e) {
            throw new TranscodeException("that video can't be read", "duration '" + duration + "'");
        }
    }

    /**
     * Runs a command, returning its output; throws with the output's tail if it fails, and
     * kills it if it runs longer than {@link #TIMEOUT_MINUTES}.
     *
     * <p>The output goes to a temp file, not a pipe: reading a pipe to the end blocks until
     * the process exits, which would make the time limit unreachable, and an unread pipe
     * can fill up and stall ffmpeg.
     */
    static String run(List<String> command) throws TranscodeException {
        return run(command, TimeUnit.MINUTES.toMillis(TIMEOUT_MINUTES));
    }

    static String run(List<String> command, long timeoutMillis) throws TranscodeException {
        Path log = null;
        Process p = null;
        try {
            log = Files.createTempFile("ffmpeg-", ".log");
            p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            p.getOutputStream().close(); // no input: ffmpeg never waits on stdin
            if (!p.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                throw new TranscodeException("processing took too long", command.get(0) + " timed out");
            }
            String text = Files.readString(log, StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                String tail = text.length() > 500 ? text.substring(text.length() - 500) : text;
                throw new TranscodeException("that video can't be processed", command.get(0) + ": " + tail.trim());
            }
            return text;
        } catch (IOException e) {
            throw new TranscodeException("processing isn't available right now", e.toString());
        } catch (InterruptedException e) {
            if (p != null) p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new TranscodeException("processing was interrupted", e.toString());
        } finally {
            if (log != null) {
                try {
                    Files.deleteIfExists(log);
                } catch (IOException ignored) {
                    // A temp file: the OS cleans it up eventually.
                }
            }
        }
    }
}
