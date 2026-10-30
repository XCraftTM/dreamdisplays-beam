package dev.xcrafttm.ddbeam.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.xcrafttm.ddbeam.DdBeam;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Client settings, {@code config/ddbeam.json}. */
public final class BeamConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static BeamConfig instance = new BeamConfig();

    /** FFmpeg to use; empty: the one Dream Displays downloaded, else the one on the PATH. */
    public String ffmpegPath = "";
    /** Largest picture that is captured; the display's shape decides the actual size within it. */
    public int maxWidth = 1280;
    public int maxHeight = 720;
    /** Frames per second that are captured and shown on your own display. */
    public int fps = 30;
    /** JPEG quality, FFmpeg's scale: 2 (best, largest) to 31 (worst, smallest). */
    public int quality = 5;
    /** How the picture fills a display of another shape: "fit" (black bars), "fill" (cropped) or "stretch". */
    public String fit = "fit";
    /** Draw the mouse pointer when casting a monitor. */
    public boolean showCursor = true;
    /** Stream casts to the other players near the display (needs Beam on the server). */
    public boolean share = true;
    /** The other players get the cast as H.264 video: "auto" (hardware first, then libx264) or an FFmpeg encoder. */
    public String streamEncoder = "auto";
    /** "auto": HEVC when every Beam player on the server can decode it and there is an HEVC encoder here; "h264". */
    public String streamCodec = "auto";
    /** Bit rate of that video in kbit/s; 0: from the size and frame rate (about 12000 for 1440p60). */
    public int streamBitrateKbps = 0;
    /** Only when no H.264 encoder works: JPEG frames per second that are streamed to the other players. */
    public int shareFps = 15;
    /** Show what other players cast. */
    public boolean showOtherCasts = true;
    /** Windows: "gdigrab" (works everywhere) or "ddagrab" (Desktop Duplication: faster, also catches games). */
    public String windowsGrabber = "gdigrab";
    /** Camera picture size to ask for, e.g. "1280x720"; empty: the camera's default. */
    public String cameraSize = "";
    /** The mode picked in the cast dialog per camera (by device name), see CameraModes; none: the camera's default. */
    public java.util.Map<String, String> cameraModes = new java.util.HashMap<>();

    /**
     * Stream what the computer plays along with a screen or window cast (you do not hear it twice: only the
     * other players get it). Windows leaves the game's own sound out; Linux takes the output's monitor (game
     * included); macOS needs a loopback input such as BlackHole set in {@link #systemAudioDevice}.
     */
    public boolean systemAudio = true;
    /** Linux / macOS: the input to use as system audio (a PulseAudio source or an AVFoundation device name). */
    public String systemAudioDevice = "";
    /** A camera's audio: "auto" (the input named like the camera), "none", or an input's name. */
    public String cameraAudio = "auto";
    /** The audio input picked in the cast dialog per camera (by device name), like {@link #cameraAudio}; none: that. */
    public java.util.Map<String, String> cameraMicrophones = new java.util.HashMap<>();
    /** Volume of cast audio (yours and other players'). */
    public float volume = 1.0f;
    /** Cast audio fades out over this many blocks from the display. */
    public float audioRange = 32f;

    public static BeamConfig get() {
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddbeam.json");
    }

    public static void load() {
        Path path = path();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                BeamConfig read = GSON.fromJson(reader, BeamConfig.class);
                if (read != null) instance = read;
            } catch (IOException | RuntimeException e) {
                DdBeam.LOGGER.warn("Could not read {}, using the defaults: {}", path, e.toString());
            }
        }
        save();
    }

    public static void save() {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(instance, writer);
            }
        } catch (IOException e) {
            DdBeam.LOGGER.warn("Could not write {}: {}", path, e.toString());
        }
    }
}
