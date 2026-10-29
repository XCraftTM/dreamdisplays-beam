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

    /** Stream thread: a piece of the H.264 video for the other players, sent as it comes. */
    private void onStream(byte[] data) {
        if (ended || !serverShares) return;
        streamBytes += data.length;
        // in packets below Minecraft's size limit
        for (int at = 0; at < data.length; at += BeamPayloads.MAX_PART) {
            byte[] piece = java.util.Arrays.copyOfRange(data, at, Math.min(data.length, at + BeamPayloads.MAX_PART));
            int s = seq++;
            Minecraft.getInstance().execute(() -> {
                if (Casts.local() == this && BeamClientNet.canSend(BeamPayloads.STREAM)) {
                    BeamClientNet.send(BeamPayloads.STREAM, new BeamPayloads.Stream(display, NOBODY, s, width, height, piece).encode());
                }
            });
        }
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
        capture.close();
        AudioCapture a = audio;
        if (a != null) a.close();
        if (localAudio != null) localAudio.close();
        feed.close();
        if (BeamClientNet.canSend(BeamPayloads.STOP)) BeamClientNet.send(BeamPayloads.STOP, new BeamPayloads.Stop(display).encode());
    }
}
