package dev.xcrafttm.ddbeam.client.capture;

import dev.xcrafttm.ddbeam.DdBeam;
import dev.xcrafttm.ddbeam.client.BeamConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Finds the FFmpeg binary: the configured one, the one Dream Displays downloaded, or the one on the PATH. */
public final class Ffmpeg {
    private static volatile String path;

    private Ffmpeg() {
    }

    /** Can block (Dream Displays may still be downloading FFmpeg): never call it on the render thread. */
    public static String path() {
        String configured = BeamConfig.get().ffmpegPath;
        if (configured != null && !configured.isBlank()) return configured;
        if (path != null) return path;
        String found = null;
        try {
            found = com.dreamdisplays.media.player.process.FFmpegBinary.INSTANCE.getPath();
        } catch (RuntimeException | LinkageError e) {
            DdBeam.LOGGER.warn("Dream Displays' FFmpeg is not available ({}); using the one on the PATH", e.toString());
        }
        path = found != null ? found : "ffmpeg";
        DdBeam.LOGGER.info("FFmpeg: {}", path);
        return path;
    }

    /** Runs FFmpeg with these arguments and returns what it wrote to stderr (where it lists devices). */
    public static String run(List<String> args, long timeoutMs) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(path());
        command.add("-hide_banner");
        command.addAll(args);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.PIPE).start();
        process.getOutputStream().close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(out);
            } catch (IOException ignored) {
            }
        }, "ddbeam-ffmpeg-probe");
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        reader.join(1000);
        return out.toString(StandardCharsets.UTF_8);
    }
}
