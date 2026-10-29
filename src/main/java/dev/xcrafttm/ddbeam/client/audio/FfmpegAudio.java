package dev.xcrafttm.ddbeam.client.audio;

import dev.xcrafttm.ddbeam.DdBeam;
import dev.xcrafttm.ddbeam.client.capture.Ffmpeg;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Audio through FFmpeg (a camera's or capture card's audio device, or the test tone), as 48 kHz mono PCM. */
public final class FfmpegAudio implements AudioCapture {
    private final List<String> input;
    private volatile Process process;
    private volatile boolean closed;

    /** @param input FFmpeg arguments up to and including {@code -i} */
    private FfmpegAudio(List<String> input) {
        this.input = input;
    }

    /** A DirectShow (Windows) audio device by name. */
    public static FfmpegAudio dshow(String device) {
        return new FfmpegAudio(List.of("-f", "dshow", "-audio_buffer_size", "40", "-i", "audio=" + device));
    }

    /** PulseAudio / PipeWire source by name; {@code @DEFAULT_MONITOR@} is what the computer plays. */
    public static FfmpegAudio pulse(String source) {
        return new FfmpegAudio(List.of("-f", "pulse", "-i", source));
    }

    /** macOS: an AVFoundation audio input by its index. */
    public static FfmpegAudio avfoundation(String index) {
        return new FfmpegAudio(List.of("-f", "avfoundation", "-i", ":" + index));
    }

    public static FfmpegAudio testTone() {
        return new FfmpegAudio(List.of("-re", "-f", "lavfi", "-i", "sine=frequency=440:beep_factor=4:sample_rate=48000"));
    }

    /** Starts FFmpeg (blocks while it is located: not on the render thread). */
    public FfmpegAudio start(Consumer<short[]> sink) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Ffmpeg.path());
        command.addAll(List.of("-hide_banner", "-loglevel", "error", "-nostdin"));
        command.addAll(input);
        command.addAll(List.of("-vn", "-ac", "1", "-ar", Integer.toString(RATE), "-f", "s16le", "pipe:1"));
        DdBeam.LOGGER.info("Capturing audio: {}", String.join(" ", command));
        Process started = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        process = started;
        if (closed) started.destroyForcibly();
        Thread reader = new Thread(() -> {
            // 10 ms per read: the sink collects them into packets
            byte[] buf = new byte[RATE / 100 * 2];
            try (InputStream in = started.getInputStream()) {
                while (!closed) {
                    int n = in.readNBytes(buf, 0, buf.length);
                    if (n < 2) break;
                    short[] pcm = new short[n / 2];
                    for (int i = 0; i < pcm.length; i++) pcm[i] = (short) ((buf[2 * i] & 0xFF) | (buf[2 * i + 1] << 8));
                    sink.accept(pcm);
                }
            } catch (IOException ignored) {
            }
            if (!closed) DdBeam.LOGGER.warn("Audio capture ended (exit {})", started.isAlive() ? "-" : started.exitValue());
        }, "ddbeam-audio-ffmpeg");
        reader.setDaemon(true);
        reader.start();
        return this;
    }

    @Override
    public void close() {
        closed = true;
        Process p = process;
        if (p != null) p.destroyForcibly();
    }
}
