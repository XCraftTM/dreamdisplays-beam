package dev.xcrafttm.ddbeam.client.cast;

import dev.xcrafttm.ddbeam.DdBeam;
import dev.xcrafttm.ddbeam.client.capture.Ffmpeg;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decodes another player's H.264 cast stream: the pieces go into an FFmpeg process (Dream Displays' FFmpeg, with
 * hardware decoding where there is some), raw RGBA frames of the cast's size come out and go to the feed.
 */
final class StreamDecoder {
    /** More than this waiting for FFmpeg means it fell behind: drop it and pick up at the next key frame. */
    private static final long MAX_QUEUED = 8L << 20;
    private static final byte[] END = new byte[0];

    final int width, height;
    private final CastFeed feed;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final AtomicLong queued = new AtomicLong();
    private volatile Process process;
    private volatile boolean closed;

    StreamDecoder(CastFeed feed, int width, int height) {
        this.feed = feed;
        this.width = width;
        this.height = height;
        Thread starter = new Thread(this::run, "ddbeam-stream-decoder");
        starter.setDaemon(true);
        starter.start();
    }

    /** A piece of the stream, in order. Any thread. */
    void write(byte[] data) {
        if (closed) return;
        if (queued.addAndGet(data.length) > MAX_QUEUED) {
            queue.clear();
            queued.set(data.length);
        }
        queue.add(data);
    }

    private void run() {
        List<String> command = List.of(Ffmpeg.path(), "-hide_banner", "-loglevel", "error",
                "-fflags", "nobuffer+discardcorrupt", "-flags", "low_delay", "-probesize", "65536", "-analyzeduration", "0",
                "-hwaccel", "auto", "-f", "mpegts", "-i", "pipe:0",
                "-an", "-sn", "-vf", "scale=" + width + ":" + height, "-pix_fmt", "rgba", "-fps_mode", "passthrough",
                "-f", "rawvideo", "pipe:1");
        Process p;
        try {
            p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            DdBeam.LOGGER.warn("Could not start the decoder for a cast: {}", e.toString());
            return;
        }
        process = p;
        if (closed) {
            p.destroyForcibly();
            return;
        }
        Thread writer = new Thread(() -> writeLoop(p), "ddbeam-stream-writer");
        writer.setDaemon(true);
        writer.start();
        int frameBytes = width * height * 4;
        try (InputStream in = p.getInputStream()) {
            byte[] chunk = new byte[256 * 1024];
            while (!closed) {
                ByteBuffer frame = MemoryUtil.memAlloc(frameBytes);
                int got = 0;
                while (got < frameBytes) {
                    int n = in.read(chunk, 0, Math.min(chunk.length, frameBytes - got));
                    if (n < 0) break;
                    frame.put(got, chunk, 0, n);
                    got += n;
                }
                if (got < frameBytes) {
                    MemoryUtil.memFree(frame);
                    break;
                }
                feed.submitDecoded(frame, width, height);
            }
        } catch (IOException ignored) {
        }
        if (!closed) DdBeam.LOGGER.warn("The decoder of a cast stopped (exit {})", p.isAlive() ? "-" : p.exitValue());
    }

    private void writeLoop(Process p) {
        try (OutputStream out = p.getOutputStream()) {
            while (!closed) {
                byte[] data = queue.poll(500, TimeUnit.MILLISECONDS);
                if (data == null) continue;
                if (data == END) break;
                queued.addAndGet(-data.length);
                out.write(data);
                out.flush();
            }
        } catch (IOException | InterruptedException ignored) {
        }
    }

    void close() {
        closed = true;
        queue.clear();
        queue.add(END);
        Process p = process;
        if (p != null) p.destroyForcibly();
    }
}
