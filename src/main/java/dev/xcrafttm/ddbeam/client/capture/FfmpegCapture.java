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
 * <p>
 * When the cast is shared, the same process also encodes the picture as H.264 in MPEG-TS for the other players
 * (same size and frame rate) and sends it to a local socket; its data goes to {@code onStream}. MPEG-TS lets a
 * player start decoding anywhere, at the next key frame.
 */
public final class FfmpegCapture implements AutoCloseable {
    private final CaptureSource source;
    private final int width, height, fps;
    private final Consumer<byte[]> onFrame;
    private final Consumer<byte[]> onStream;
    private final Consumer<String> onEnd;
    private volatile boolean streaming;
    private volatile java.net.ServerSocket streamSocket;
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
        this(source, width, height, fps, onFrame, null, onEnd);
    }

    /** @param onStream gets the H.264 stream for the other players; null: no stream */
    public FfmpegCapture(CaptureSource source, int width, int height, int fps, Consumer<byte[]> onFrame,
                         Consumer<byte[]> onStream, Consumer<String> onEnd) {
        this.source = source;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.onFrame = onFrame;
        this.onStream = onStream;
        this.onEnd = onEnd;
    }

    /** True once FFmpeg encodes the H.264 stream for the other players (else they get the JPEG frames). */
    public boolean streaming() {
        return streaming;
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
        return scaleFilter() + ",format=yuvj420p";
    }

    private String scaleFilter() {
        String size = width + ":" + height;
        String scale = switch (BeamConfig.get().fit.toLowerCase(Locale.ROOT)) {
            case "stretch" -> "scale=" + size;
            case "fill" -> "scale=" + size + ":force_original_aspect_ratio=increase,crop=" + size;
            default -> "scale=" + size + ":force_original_aspect_ratio=decrease,pad=" + size + ":(ow-iw)/2:(oh-ih)/2:black";
        };
        return "fps=" + fps + "," + scale + ",setsar=1";
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
            String encoder = onStream != null ? VideoEncoder.get() : null;
            if (encoder != null) command.addAll(VideoEncoder.deviceArgs(encoder));
            command.addAll(source.inputArgs(fps));
            if (encoder != null) {
                // one capture, two outputs: JPEG frames for this display, H.264 for the other players
                java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
                streamSocket = socket;
                int kbps = VideoEncoder.bitrateKbps(width, height, fps);
                command.addAll(List.of("-an", "-filter_complex", "[0:v]" + scaleFilter()
                                + ",split=2[a][b];[a]format=yuvj420p[local];[b]format=" + VideoEncoder.inputFormat(encoder) + "[net]",
                        "-map", "[local]", "-c:v", "mjpeg", "-q:v", Integer.toString(quality), "-f", "mpjpeg", "pipe:1",
                        "-map", "[net]"));
                command.addAll(VideoEncoder.options(encoder, kbps, fps));
                command.addAll(List.of("-f", "mpegts", "-muxdelay", "0", "-muxpreload", "0", "-flush_packets", "1",
                        "tcp://127.0.0.1:" + socket.getLocalPort()));
                streaming = true;
                Thread stream = new Thread(() -> readStream(socket), "ddbeam-ffmpeg-stream");
                stream.setDaemon(true);
                stream.start();
            } else {
                command.addAll(List.of("-an", "-vf", filter(), "-c:v", "mjpeg",
                        "-q:v", Integer.toString(quality), "-f", "mpjpeg", "pipe:1"));
            }
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

    /** The H.264 stream: handed on in pieces as FFmpeg writes them (a piece ends where FFmpeg pauses). */
    private void readStream(java.net.ServerSocket socket) {
        try (socket) {
            socket.setSoTimeout(15_000);
            try (java.net.Socket client = socket.accept(); InputStream in = client.getInputStream()) {
                byte[] buf = new byte[64 * 1024];
                java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream(128 * 1024);
                int n;
                while (!closed && (n = in.read(buf)) != -1) {
                    pending.write(buf, 0, n);
                    if (pending.size() >= 64 * 1024 || in.available() == 0) {
                        onStream.accept(pending.toByteArray());
                        pending.reset();
                    }
                }
            }
        } catch (IOException e) {
            if (!closed) DdBeam.LOGGER.warn("The stream for the other players stopped: {}", e.toString());
        }
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
        java.net.ServerSocket socket = streamSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
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
