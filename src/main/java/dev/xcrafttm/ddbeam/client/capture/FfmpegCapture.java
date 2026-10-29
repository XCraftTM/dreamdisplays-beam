package dev.xcrafttm.ddbeam.client.capture;

import dev.xcrafttm.ddbeam.DdBeam;
import dev.xcrafttm.ddbeam.client.BeamConfig;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One FFmpeg process that captures a source and writes it as a stream of JPEG frames ({@code mpjpeg}: every frame
 * comes with its length, so frames are cut exactly). The frames go to {@code onFrame} on the reader thread.
 */
public final class FfmpegCapture implements AutoCloseable {
    private final CaptureSource source;
    private final int width, height, fps;
    private final Consumer<byte[]> onFrame;
    private final Consumer<String> onEnd;
    private final ArrayDeque<String> lastLines = new ArrayDeque<>();
    private volatile Process process;
    private volatile boolean closed;
    private volatile long frames;
    private volatile int exitCode = -1;

    /**
     * @param width  picture size the frames get (the display's shape)
     * @param onEnd  called once if FFmpeg ends by itself, with its last error output
     */
    public FfmpegCapture(CaptureSource source, int width, int height, int fps, Consumer<byte[]> onFrame, Consumer<String> onEnd) {
        this.source = source;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.onFrame = onFrame;
        this.onEnd = onEnd;
    }

    public long frames() {
        return frames;
    }

    /** The exit code once FFmpeg (or the Wayland helper) ended by itself, else -1. */
    public int exitCode() {
        return exitCode;
    }

    public CaptureSource source() {
        return source;
    }

    /** The scale filter for the configured fit, ending in the pixel format the JPEG encoder takes. */
    private String filter() {
        String size = width + ":" + height;
        String scale = switch (BeamConfig.get().fit.toLowerCase(Locale.ROOT)) {
            case "stretch" -> "scale=" + size;
            case "fill" -> "scale=" + size + ":force_original_aspect_ratio=increase,crop=" + size;
            default -> "scale=" + size + ":force_original_aspect_ratio=decrease,pad=" + size + ":(ow-iw)/2:(oh-ih)/2:black";
        };
        return "fps=" + fps + "," + scale + ",setsar=1,format=yuvj420p";
    }

    /** Starts FFmpeg and its reader threads; blocks while FFmpeg is located (do not call it on the render thread). */
    public void start() throws IOException {
        int quality = Math.clamp(BeamConfig.get().quality, 2, 31);
        List<String> command = new ArrayList<>();
        if (source.portal()) {
            // Wayland: the desktop portal + PipeWire, through the helper (same multipart JPEG output)
            command.addAll(List.of("python3", WaylandPortal.script().toString(), Integer.toString(width), Integer.toString(height),
                    Integer.toString(fps), Integer.toString(Math.max(30, 100 - quality * 3)), BeamConfig.get().showCursor ? "1" : "0",
                    source.kind() == CaptureSource.Kind.WINDOW ? "2" : "1", WaylandPortal.tokenFile(source.kind()).toString()));
        } else {
            command.add(Ffmpeg.path());
            command.addAll(List.of("-hide_banner", "-loglevel", "error", "-nostdin"));
            command.addAll(source.inputArgs(fps));
            command.addAll(List.of("-an", "-vf", filter(), "-c:v", "mjpeg",
                    "-q:v", Integer.toString(quality), "-f", "mpjpeg", "pipe:1"));
        }
        DdBeam.LOGGER.info("Capturing {}: {}", source.kind(), String.join(" ", command));
        Process started = new ProcessBuilder(command).start();
        if (closed) {
            started.destroyForcibly();
            return;
        }
        process = started;
        Thread errors = new Thread(() -> readErrors(started), "ddbeam-ffmpeg-log");
        errors.setDaemon(true);
        errors.start();
        Thread frames = new Thread(() -> readFrames(started), "ddbeam-ffmpeg-frames");
        frames.setDaemon(true);
        frames.start();
    }

    private void readErrors(Process p) {
        try (InputStream in = p.getErrorStream()) {
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c != '\n') {
                    line.append((char) c);
                    continue;
                }
                String text = line.toString().trim();
                line.setLength(0);
                if (text.isEmpty()) continue;
                synchronized (lastLines) {
                    lastLines.addLast(text);
                    if (lastLines.size() > 6) lastLines.removeFirst();
                }
                DdBeam.LOGGER.warn("FFmpeg: {}", text);
            }
        } catch (IOException ignored) {
        }
    }

    private void readFrames(Process p) {
        try (InputStream in = new BufferedInputStream(p.getInputStream(), 1 << 16)) {
            while (!closed) {
                int length = readHeader(in);
                if (length < 0) break;
                byte[] jpeg = in.readNBytes(length);
                if (jpeg.length < length) break;
                frames++;
                onFrame.accept(jpeg);
            }
        } catch (IOException | RuntimeException e) {
            if (!closed) DdBeam.LOGGER.warn("Reading the capture failed: {}", e.toString());
        }
        if (!closed) {
            try {
                // let the error reader catch FFmpeg's last words
                p.waitFor(2, TimeUnit.SECONDS);
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            String last;
            synchronized (lastLines) {
                last = String.join("\n", lastLines);
            }
            if (!p.isAlive()) exitCode = p.exitValue();
            onEnd.accept(last);
        }
    }

    /** Skips to the next part of the multipart stream and returns its Content-length, -1 at the end. */
    private static int readHeader(InputStream in) throws IOException {
        int length = -1;
        boolean inHeaders = false;
        while (true) {
            String line = readLine(in);
            if (line == null) return -1;
            if (!inHeaders) {
                if (line.startsWith("--")) inHeaders = true;
                continue;
            }
            if (line.isEmpty()) {
                if (length >= 0) return length;
                inHeaders = false;
                continue;
            }
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                length = Integer.parseInt(line.substring(colon + 1).trim());
                if (length <= 0 || length > 16 << 20) throw new IOException("bad frame length " + length);
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return line.toString().replace("\r", "");
            if (line.length() < 1024) line.append((char) c);
        }
        return line.isEmpty() ? null : line.toString();
    }

    @Override
    public void close() {
        closed = true;
        Process p = process;
        if (p == null) return;
        p.destroy();
        Thread killer = new Thread(() -> {
            try {
                if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) p.destroyForcibly();
            } catch (InterruptedException ignored) {
                p.destroyForcibly();
            }
        }, "ddbeam-ffmpeg-stop");
        killer.setDaemon(true);
        killer.start();
    }

    @Override
    public String toString() {
        return source.kind() + " " + width + "x" + height + "@" + fps;
    }
}
