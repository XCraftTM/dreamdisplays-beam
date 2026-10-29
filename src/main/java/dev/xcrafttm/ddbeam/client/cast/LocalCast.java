package dev.xcrafttm.ddbeam.client.cast;

import dev.xcrafttm.ddbeam.Texts;
import dev.xcrafttm.ddbeam.DdBeam;
import dev.xcrafttm.ddbeam.client.BeamConfig;
import dev.xcrafttm.ddbeam.client.audio.Adpcm;
import dev.xcrafttm.ddbeam.client.audio.AudioCapture;
import dev.xcrafttm.ddbeam.client.audio.AudioPlayer;
import dev.xcrafttm.ddbeam.client.audio.FfmpegAudio;
import dev.xcrafttm.ddbeam.client.audio.WasapiLoopback;
import dev.xcrafttm.ddbeam.client.capture.CaptureSource;
import dev.xcrafttm.ddbeam.client.capture.FfmpegCapture;
import dev.xcrafttm.ddbeam.client.capture.Sources;
import dev.xcrafttm.ddbeam.client.capture.WaylandPortal;
import dev.xcrafttm.ddbeam.net.BeamPayloads;
import dev.xcrafttm.ddbeam.net.Congestion;
import dev.xcrafttm.ddbeam.net.TsUnits;
import dev.xcrafttm.ddbeam.client.BeamClientNet;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * This player's cast: one source captured onto one display, shown here right away and streamed to the server
 * when it relays casts.
 * <p>
 * Audio: a screen or window cast takes what the computer (or that application) plays and only streams it, since
 * the caster hears it already; a camera cast takes the camera's own audio input and plays it here too, from the
 * display, like the other players hear it.
 */
public final class LocalCast {
    private static final UUID NOBODY = new UUID(0, 0);
    /** 40 ms of audio per packet. */
    private static final int AUDIO_PACKET = AudioCapture.RATE / 25;
    /**
     * The video goes out paced: the next piece only when the game connection has less than this waiting on it and is
     * not slower than usual (see DelayProbe), so the game's own packets (the answer to the server's ping too) get in
     * between, also between the pieces of a big key frame.
     */
    private static final long PACE_LIMIT = 64 * 1024, PACE_LATE_MS = 40;
    /** Video waiting for the pacer above which frames are skipped (up to the next key frame that finds it empty). */
    private static final long OUTBOX_LIMIT = 192 * 1024;
    private static final boolean NO_GATE = Boolean.getBoolean("ddbeam.noStreamGate");
    /** Extra delay on the game connection (see DelayProbe) at which frames are skipped, and below which they resume. */
    private static final long BEHIND_MS = 200, CAUGHT_UP_MS = 60;
    private static final byte[] END = new byte[0];

    /** What the dialog chose. */
    public record Settings(CaptureSource source, int maxWidth, int maxHeight, int fps, boolean share, boolean audio) {
    }

    public final UUID display;
    public final CaptureSource source;
    public final Settings settings;
    public final int width, height;
    final CastFeed feed;
    private final FfmpegCapture capture;
    private volatile AudioCapture audio;
    /** Camera and test sources: the caster hears them from the display too. */
    final AudioPlayer localAudio;
    private final short[] audioBuffer = new short[AUDIO_PACKET];
    private int audioFill;
    private volatile boolean ended;
    private volatile boolean serverShares;
    private int seq, audioSeq;
    private long lastSentNs;
    private volatile byte[] lastJpeg;
    private volatile String audioKind = "";
    private volatile long streamBytes;
    private final TsUnits.Splitter splitter = new TsUnits.Splitter();
    private final TsUnits.Gate gate = new TsUnits.Gate();
    /** Video handed to the client thread that is not on the connection yet. */
    private final java.util.concurrent.atomic.AtomicLong queued = new java.util.concurrent.atomic.AtomicLong();
    /** Pieces waiting for the pacer, and their bytes. */
    private final java.util.concurrent.LinkedBlockingQueue<byte[]> outbox = new java.util.concurrent.LinkedBlockingQueue<>();
    private final java.util.concurrent.atomic.AtomicLong outboxBytes = new java.util.concurrent.atomic.AtomicLong();
    private Thread pacer;
    private volatile long skippedUnits;
    final DelayProbe probe = new DelayProbe();

    LocalCast(Targets.Target target, Settings settings) {
        this.display = target.id();
        this.source = settings.source();
        this.settings = settings;
        int[] size = target.pictureSize(settings.maxWidth(), settings.maxHeight());
        this.width = size[0];
        this.height = size[1];
        this.feed = new CastFeed(display, null);
        boolean hearHere = source.kind() == CaptureSource.Kind.CAMERA || source.kind() == CaptureSource.Kind.TEST;
        this.localAudio = settings.audio() && hearHere ? new AudioPlayer() : null;
        this.capture = new FfmpegCapture(source, width, height, settings.fps(), this::onFrame,
                settings.share() ? this::onStream : null, this::onEnd);
    }

    /** Starts capturing in the background (FFmpeg may have to be found or downloaded first). */
    void start() {
        Thread starter = new Thread(() -> {
            try {
                capture.start();
                if (settings.audio()) startAudio();
            } catch (Exception e) {
                DdBeam.LOGGER.warn("Could not start the cast: {}", e.toString());
                fail(Texts.tr("ddbeam.error.start", e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }, "ddbeam-cast-start");
        starter.setDaemon(true);
        starter.start();
        Minecraft.getInstance().execute(() -> {
            if (BeamClientNet.canSend(BeamPayloads.START) && settings.share()) {
                BeamClientNet.send(BeamPayloads.START, new BeamPayloads.Start(display, width, height).encode());
            }
        });
    }

    private void startAudio() throws Exception {
        BeamConfig config = BeamConfig.get();
        String os = Sources.os();
        switch (source.kind()) {
            case MONITOR, WINDOW -> {
                // only the others hear it: without sharing there is no point capturing it
                if (!settings.share() || !config.systemAudio) return;
                if (os.equals("windows")) {
                    boolean app = source.kind() == CaptureSource.Kind.WINDOW && source.index() > 0;
                    audio = app ? WasapiLoopback.startProcess(source.index(), this::onAudio, this::onAudioError)
                            : WasapiLoopback.start(this::onAudio, this::onAudioError);
                    audioKind = app ? "application" : "system";
                } else if (os.equals("linux")) {
                    String device = config.systemAudioDevice.isBlank() ? "@DEFAULT_MONITOR@" : config.systemAudioDevice;
                    audio = FfmpegAudio.pulse(device).start(this::onAudio);
                    audioKind = "system";
                } else {
                    // macOS has no loopback of its own: only a virtual input such as BlackHole works
                    Sources.AudioDevice device = config.systemAudioDevice.isBlank() ? null : Sources.audioByName(config.systemAudioDevice);
                    if (device == null) return;
                    audio = FfmpegAudio.avfoundation(device.id()).start(this::onAudio);
                    audioKind = "system";
                }
            }
            case CAMERA -> {
                if ("none".equalsIgnoreCase(config.cameraAudio)) return;
                if (Sources.cameras() == null) Sources.refresh().join();
                Sources.AudioDevice device = "auto".equalsIgnoreCase(config.cameraAudio)
                        ? Sources.audioOf(source) : Sources.audioByName(config.cameraAudio);
                if (device == null) {
                    DdBeam.LOGGER.info("No audio input found for camera {}", source.title());
                    return;
                }
                DdBeam.LOGGER.info("Camera audio: {}", device.name());
                audio = switch (os) {
                    case "windows" -> FfmpegAudio.dshow(device.id()).start(this::onAudio);
                    case "mac" -> FfmpegAudio.avfoundation(device.id()).start(this::onAudio);
                    default -> FfmpegAudio.pulse(device.id()).start(this::onAudio);
                };
                audioKind = "camera";
            }
            case TEST -> {
                audio = FfmpegAudio.testTone().start(this::onAudio);
                audioKind = "test";
            }
        }
        if (ended) {
            AudioCapture a = audio;
            if (a != null) a.close();
        }
    }

    private void onAudioError(String error) {
        Minecraft.getInstance().execute(() -> Casts.message(Texts.tr("ddbeam.error.audio", error)));
    }

    /** Capture thread: collects 40 ms packets, plays them here (cameras) and streams them. */
    private void onAudio(short[] pcm) {
        if (ended) return;
        int at = 0;
        while (at < pcm.length) {
            int n = Math.min(pcm.length - at, AUDIO_PACKET - audioFill);
            System.arraycopy(pcm, at, audioBuffer, audioFill, n);
            audioFill += n;
            at += n;
            if (audioFill < AUDIO_PACKET) break;
            audioFill = 0;
            short[] packet = audioBuffer.clone();
            if (localAudio != null) localAudio.push(packet);
            if (serverShares) {
                byte[] data = Adpcm.encode(packet);
                int s = audioSeq++;
                Minecraft.getInstance().execute(() -> {
                    if (Casts.local() == this && BeamClientNet.canSend(BeamPayloads.AUDIO)) {
                        BeamClientNet.send(BeamPayloads.AUDIO, new BeamPayloads.Audio(display, NOBODY, s, data).encode());
                    }
                });
            }
        }
    }

    /** Stream thread: the video for the other players, sent a frame at a time as it comes. */
    private void onStream(byte[] data) {
        if (ended || !serverShares) return;
        splitter.feed(data, this::sendUnit);
    }

    /**
     * One frame (with the tables before it). When the video cannot get out as fast as it comes (too much waits for the
     * pacer, or the connection is slower than usual), the frame is skipped, and so is everything after it up to the next
     * key frame that finds the way clear: the others see the last whole picture a little longer instead of a broken one.
     */
    private void sendUnit(TsUnits.Unit unit) {
        long waiting = outboxBytes.get(), late = probe.extraMs();
        boolean congested = waiting > OUTBOX_LIMIT || late > BEHIND_MS;
        boolean roomForKey = waiting == 0 && late < CAUGHT_UP_MS;
        // -Dddbeam.noStreamGate=true: everything goes out as it comes (for comparison)
        if (!NO_GATE && !gate.pass(true, unit.key(), congested, roomForKey)) {
            skippedUnits++;
            return;
        }
        byte[] data = unit.data();
        streamBytes += data.length;
        // in packets below Minecraft's size limit, cut on the TS packet grid
        int part = BeamPayloads.MAX_PART / TsUnits.TS * TsUnits.TS;
        startPacer();
        for (int at = 0; at < data.length; at += part) {
            byte[] piece = java.util.Arrays.copyOfRange(data, at, Math.min(data.length, at + part));
            outboxBytes.addAndGet(piece.length);
            outbox.add(piece);
        }
    }

    private synchronized void startPacer() {
        if (pacer != null) return;
        pacer = new Thread(this::pace, "ddbeam-stream-pacer");
        pacer.setDaemon(true);
        pacer.start();
    }

    /** Pacer thread: hands the pieces to the client thread one at a time, when the connection has room for them. */
    private void pace() {
        try {
            while (!ended) {
                byte[] piece = outbox.take();
                if (piece == END) return;
                while (!NO_GATE && !ended) {
                    long onConnection = queued.get() + Congestion.pending(Minecraft.getInstance().getConnection());
                    if (onConnection < PACE_LIMIT && probe.extraMs() < PACE_LATE_MS) break;
                    Thread.sleep(2);
                }
                outboxBytes.addAndGet(-piece.length);
                int s = seq++;
                queued.addAndGet(piece.length);
                Minecraft.getInstance().execute(() -> {
                    queued.addAndGet(-piece.length);
                    if (Casts.local() == this && BeamClientNet.canSend(BeamPayloads.STREAM)) {
                        BeamClientNet.send(BeamPayloads.STREAM, new BeamPayloads.Stream(display, NOBODY, s, width, height, piece).encode());
                    }
                });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** How much longer than usual the game connection takes now (see DelayProbe). */
    public long extraDelayMs() {
        return probe.extraMs();
    }

    /** Frames not sent because the connection was too far behind. */
    public long skippedUnits() {
        return skippedUnits;
    }

    /** Capture thread: show it here; without an H.264 encoder stream it as JPEG at the share frame rate. */
    private void onFrame(byte[] jpeg) {
        if (ended) return;
        feed.submit(jpeg);
        if (!serverShares || capture.streaming() || jpeg.length > BeamPayloads.MAX_FRAME_BYTES) return;
        long now = System.nanoTime();
        int fps = Math.max(1, Math.min(BeamConfig.get().shareFps, settings.fps()));
        if (now - lastSentNs < 1_000_000_000L / fps) return;
        lastSentNs = now;
        lastJpeg = jpeg;
        send(jpeg);
    }

    private void send(byte[] jpeg) {
        int s = seq++;
        int parts = (jpeg.length + BeamPayloads.MAX_PART - 1) / BeamPayloads.MAX_PART;
        Minecraft.getInstance().execute(() -> {
            if (Casts.local() != this || !BeamClientNet.canSend(BeamPayloads.FRAME)) return;
            for (int i = 0; i < parts; i++) {
                byte[] part = java.util.Arrays.copyOfRange(jpeg, i * BeamPayloads.MAX_PART, Math.min(jpeg.length, (i + 1) * BeamPayloads.MAX_PART));
                BeamClientNet.send(BeamPayloads.FRAME, new BeamPayloads.Frame(display, NOBODY, s, i, parts, part).encode());
            }
        });
    }

    /** Client tick: a picture that stands still (Wayland sends no frames then) is resent once a second. */
    void tick() {
        if (serverShares && capture.streaming()) probe.tick();
        byte[] last = lastJpeg;
        long now = System.nanoTime();
        if (serverShares && !capture.streaming() && last != null && now - lastSentNs > 1_000_000_000L) {
            lastSentNs = now;
            send(last);
        }
    }

    private void onEnd(String lastError) {
        if (ended) return;
        String reason = capture.source().portal() ? WaylandPortal.reason(capture.exitCode()) : null;
        if (reason != null) {
            fail(Texts.tr(reason));
        } else {
            fail(Texts.tr("ddbeam.error.ended", lastError.isBlank() ? "-" : lastError.lines().reduce((a, b) -> b).orElse("")));
        }
    }

    private void fail(Component why) {
        Minecraft.getInstance().execute(() -> {
            if (Casts.local() != this) return;
            Casts.message(why);
            Casts.stop();
        });
    }

    /** The server's answer to {@link BeamPayloads.Start}. Client thread. */
    void onNotice(String key) {
        serverShares = key.equals("shared");
        Casts.message(Texts.tr("ddbeam.notice." + key));
    }

    public boolean sharedWithOthers() {
        return serverShares;
    }

    public String audioKind() {
        AudioCapture a = audio;
        return a == null ? "" : audioKind;
    }

    public long frames() {
        return capture.frames();
    }

    /** How the others get it: "h264" (video stream), "jpeg" (frames) or "" (not shared). */
    public String streamKind() {
        return !serverShares ? "" : capture.streaming() ? "h264" : "jpeg";
    }

    public long streamBytes() {
        return streamBytes;
    }

    /** Client thread. */
    void stop() {
        ended = true;
        outbox.add(END);
        capture.close();
        AudioCapture a = audio;
        if (a != null) a.close();
        if (localAudio != null) localAudio.close();
        feed.close();
        if (BeamClientNet.canSend(BeamPayloads.STOP)) BeamClientNet.send(BeamPayloads.STOP, new BeamPayloads.Stop(display).encode());
    }
}
