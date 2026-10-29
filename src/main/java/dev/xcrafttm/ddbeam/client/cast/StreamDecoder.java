package dev.xcrafttm.ddbeam.client.cast;

import dev.xcrafttm.ddbeam.net.TsUnits;

import dev.xcrafttm.ddbeam.DdBeam;
import dev.xcrafttm.ddbeam.client.capture.Ffmpeg;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decodes another player's H.264 cast stream: the pieces go into an FFmpeg process (Dream Displays' FFmpeg, with
 * hardware decoding where there is some), raw RGBA frames of the cast's size come out and go to the feed.
 * <p>
 * A player usually comes in while a cast runs, in the middle of the stream. FFmpeg needs the stream's parameters
 * (SPS) right away to start, so the pieces are held back until the next key frame's SPS (one every two seconds) and
 * fed from the MPEG-TS table (PAT) just before it.
 */
final class StreamDecoder {
    /** More than this waiting for FFmpeg means it fell behind: drop it and pick up at the next key frame. */
    private static final long MAX_QUEUED = 8L << 20;
    private static final int TS = 188;
    private static final byte[] END = new byte[0];

    final int width, height;
    private final CastFeed feed;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final AtomicLong queued = new AtomicLong();
    private final ArrayDeque<String> lastErrors = new ArrayDeque<>();
    private volatile Process process;
    private volatile boolean closed, dead;
    /** Client thread: the stream before the first usable key frame. */
    private ByteArrayOutputStream waiting = new ByteArrayOutputStream();

    StreamDecoder(CastFeed feed, int width, int height) {
        this.feed = feed;
        this.width = width;
        this.height = height;
        Thread starter = new Thread(this::run, "ddbeam-stream-decoder");
        starter.setDaemon(true);
        starter.start();
    }

    /** True once FFmpeg ended: the next piece starts a new decoder. */
    boolean dead() {
        return dead;
    }

    /** A piece of the stream, in order. Client thread. */
    void write(byte[] data) {
        if (closed || dead) return;
        if (waiting != null) {
            waiting.write(data, 0, data.length);
            byte[] held = waiting.toByteArray();
            int from = startOf(held);
            if (from < 0) {
                // keep the last two seconds or so
                if (held.length > (4 << 20)) {
                    waiting = new ByteArrayOutputStream();
                    waiting.write(held, held.length - (1 << 20), 1 << 20);
                }
                return;
            }
            waiting = null;
            data = java.util.Arrays.copyOfRange(held, from, held.length);
        }
        if (queued.addAndGet(data.length) > MAX_QUEUED) {
            // FFmpeg cannot keep up: what waits is dropped, and the stream picks up again at the next key frame
            // (going on in the middle of a frame would show broken pictures up to there)
            queue.clear();
            queued.set(0);
            waiting = new ByteArrayOutputStream();
            write(data);
            return;
        }
        queue.add(data);
    }

    /**
     * Where FFmpeg can start: the last PAT packet before a packet that holds a key frame's parameters (an H.264 SPS, or
     * HEVC's VPS or SPS, see {@link TsUnits#isKeyNal}), or -1.
     * MPEG-TS packets are 188 bytes, each starting with 0x47.
     */
    static int startOf(byte[] d) {
        int sync = -1;
        for (int i = 0; i + 2 * TS < d.length; i++) {
            if (d[i] == 0x47 && d[i + TS] == 0x47 && d[i + 2 * TS] == 0x47) {
                sync = i;
                break;
            }
        }
        if (sync < 0) return -1;
        int lastPat = -1;
        for (int p = sync; p + TS <= d.length; p += TS) {
            if (d[p] != 0x47) return -1;
            int pid = ((d[p + 1] & 0x1F) << 8) | (d[p + 2] & 0xFF);
            if (pid == 0) {
                lastPat = p;
                continue;
            }
            if (lastPat < 0) continue;
            int afc = (d[p + 3] >> 4) & 0x3;
            int payload = p + 4;
            if (afc == 2) continue;
            if (afc == 3) payload += 1 + (d[p + 4] & 0xFF);
            for (int i = payload; i + 4 < p + TS; i++) {
                if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1 && TsUnits.isKeyNal(d[i + 3] & 0xFF, d[i + 4] & 0xFF)) return lastPat;
            }
        }
        return -1;
    }

    private void run() {
        List<String> command = List.of(Ffmpeg.path(), "-hide_banner", "-loglevel", "error",
                // no "-fflags nobuffer": it throws away what FFmpeg reads while probing, the very key frame it starts at
                "-fflags", "discardcorrupt", "-flags", "low_delay", "-probesize", "500000", "-analyzeduration", "0",
                "-hwaccel", "auto", "-f", "mpegts", "-i", "pipe:0",
                "-an", "-sn", "-vf", "scale=" + width + ":" + height, "-pix_fmt", "rgba", "-fps_mode", "passthrough",
                "-f", "rawvideo", "pipe:1");
        Process p;
        try {
            p = new ProcessBuilder(command).start();
        } catch (IOException e) {
            DdBeam.LOGGER.warn("Could not start the decoder for a cast: {}", e.toString());
            dead = true;
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
        Thread errors = new Thread(() -> readErrors(p), "ddbeam-stream-log");
        errors.setDaemon(true);
        errors.start();
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
        dead = true;
        if (!closed) {
            try {
                p.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            String why;
            synchronized (lastErrors) {
                why = String.join(" | ", lastErrors);
            }
            DdBeam.LOGGER.warn("The decoder of a cast stopped (exit {}): {}", p.isAlive() ? "-" : p.exitValue(), why.isEmpty() ? "-" : why);
            p.destroyForcibly();
        }
    }

    private void readErrors(Process p) {
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (lastErrors) {
                    lastErrors.addLast(line.trim());
                    if (lastErrors.size() > 4) lastErrors.removeFirst();
                }
            }
        } catch (IOException ignored) {
        }
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
