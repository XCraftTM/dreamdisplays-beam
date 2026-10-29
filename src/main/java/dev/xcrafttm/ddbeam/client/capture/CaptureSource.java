package dev.xcrafttm.ddbeam.client.capture;

import dev.xcrafttm.ddbeam.client.BeamConfig;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Something that can be cast: a monitor, a camera, a window or the test pattern, and the FFmpeg input arguments
 * that capture it.
 *
 * @param index monitor: its number (for ddagrab), camera: its device index (macOS)
 * @param name  monitor on macOS: its AVFoundation device index; camera: the device name (Windows, macOS) or path
 *              (Linux); window: its title
 * @param title what people know a camera by (Linux: the sysfs name of the device)
 */
public record CaptureSource(Kind kind, int index, String name, int x, int y, int width, int height, String title) {
    public enum Kind { MONITOR, CAMERA, WINDOW, TEST }

    public CaptureSource(Kind kind, int index, String name, int x, int y, int width, int height) {
        this(kind, index, name, x, y, width, height, name);
    }

    /** Linux under Wayland: the desktop's own picker chooses the screen or window (see WaylandPortal). */
    public static CaptureSource portal(Kind kind) {
        return new CaptureSource(kind, 0, "portal", 0, 0, 0, 0);
    }

    public boolean portal() {
        return (kind == Kind.MONITOR || kind == Kind.WINDOW) && "portal".equals(name) && Sources.os().equals("linux");
    }

    /** Same source, for picking it again from a fresh list. */
    public String key() {
        return kind + ":" + index + ":" + name;
    }

    public static CaptureSource test() {
        return new CaptureSource(Kind.TEST, 0, "", 0, 0, 1280, 720);
    }

    /** A window by title; its process (for its own audio) is not known. */
    public static CaptureSource window(String title) {
        return new CaptureSource(Kind.WINDOW, 0, title, 0, 0, 0, 0);
    }

    public Component label() {
        return switch (kind) {
            case MONITOR -> portal() ? Component.translatable("ddbeam.source.portal.monitor") : width > 0 ? Component.translatable("ddbeam.source.monitor", index + 1, width, height)
                    : Component.translatable("ddbeam.source.monitor.plain", index + 1);
            case CAMERA -> Component.literal(title);
            case WINDOW -> portal() ? Component.translatable("ddbeam.source.portal.window") : Component.literal(name);
            case TEST -> Component.translatable("ddbeam.source.test");
        };
    }

    /** FFmpeg arguments that open this source, up to and including its {@code -i}. */
    public List<String> inputArgs(int fps) {
        return inputArgs(fps, false);
    }

    /** @param still for a single picture: gdigrab even where ddagrab casts (its first frame is not black) */
    public List<String> inputArgs(int fps, boolean still) {
        BeamConfig config = BeamConfig.get();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean windows = os.contains("win"), mac = os.contains("mac");
        String rate = Integer.toString(fps);
        List<String> args = new ArrayList<>();
        switch (kind) {
            case MONITOR -> {
                if (windows && !still && "ddagrab".equals(config.windowsGrabber)) {
                    args.addAll(List.of("-f", "lavfi", "-i", "ddagrab=output_idx=" + index + ":framerate=" + rate
                            + ":draw_mouse=" + (config.showCursor ? 1 : 0) + ",hwdownload,format=bgra"));
                } else if (windows) {
                    args.addAll(List.of("-f", "gdigrab", "-framerate", rate, "-draw_mouse", config.showCursor ? "1" : "0",
                            "-offset_x", Integer.toString(x), "-offset_y", Integer.toString(y),
                            "-video_size", width + "x" + height, "-i", "desktop"));
                } else if (mac) {
                    // avfoundation lists screens after the cameras; Sources stores that device index in name
                    args.addAll(List.of("-f", "avfoundation", "-framerate", rate, "-capture_cursor", config.showCursor ? "1" : "0",
                            "-i", name + ":none"));
                } else {
                    String display = System.getenv().getOrDefault("DISPLAY", ":0");
                    args.addAll(List.of("-f", "x11grab", "-framerate", rate, "-draw_mouse", config.showCursor ? "1" : "0",
                            "-video_size", width + "x" + height, "-i", display + "+" + x + "," + y));
                }
            }
            case CAMERA -> {
                if (windows) {
                    args.addAll(List.of("-f", "dshow", "-rtbufsize", "64M"));
                    if (!config.cameraSize.isBlank()) args.addAll(List.of("-video_size", config.cameraSize));
                    args.addAll(List.of("-i", "video=" + name));
                } else if (mac) {
                    args.addAll(List.of("-f", "avfoundation", "-framerate", "30"));
                    if (!config.cameraSize.isBlank()) args.addAll(List.of("-video_size", config.cameraSize));
                    args.addAll(List.of("-i", index + ":none"));
                } else {
                    args.addAll(List.of("-f", "v4l2"));
                    if (!config.cameraSize.isBlank()) args.addAll(List.of("-video_size", config.cameraSize));
                    args.addAll(List.of("-i", name));
                }
            }
            case WINDOW -> args.addAll(List.of("-f", "gdigrab", "-framerate", rate, "-draw_mouse", config.showCursor ? "1" : "0",
                    "-i", "title=" + name));
            case TEST -> args.addAll(List.of("-re", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=" + rate));
        }
        return args;
    }
}
