package dev.xcrafttm.ddbeam.client.capture;

import dev.xcrafttm.ddbeam.Texts;
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
 * @param title  what people know a camera by (Linux: the sysfs name of the device)
 * @param handle window on Windows: its HWND (0 when only the title is known)
 */
public record CaptureSource(Kind kind, int index, String name, int x, int y, int width, int height, String title, long handle) {
    public enum Kind { MONITOR, CAMERA, WINDOW, TEST }

    public CaptureSource(Kind kind, int index, String name, int x, int y, int width, int height, String title) {
        this(kind, index, name, x, y, width, height, title, 0);
    }

    public CaptureSource(Kind kind, int index, String name, int x, int y, int width, int height) {
        this(kind, index, name, x, y, width, height, name, 0);
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
        // a window keeps its handle while its title changes (browser tabs)
        return handle != 0 ? kind + ":" + handle : kind + ":" + index + ":" + name;
    }

    public static CaptureSource test() {
        return new CaptureSource(Kind.TEST, 0, "", 0, 0, 1280, 720);
    }

    /** A window by title: the open window with that title (or one containing it), else just the title. */
    public static CaptureSource window(String title) {
        CaptureSource contains = null;
        for (CaptureSource w : WindowList.list()) {
            if (w.name().equalsIgnoreCase(title)) return w;
            if (contains == null && w.name().toLowerCase(Locale.ROOT).contains(title.toLowerCase(Locale.ROOT))) contains = w;
        }
        return contains != null ? contains : new CaptureSource(Kind.WINDOW, 0, title, 0, 0, 0, 0);
    }

    public Component label() {
        return switch (kind) {
            case MONITOR -> portal() ? Texts.tr("ddbeam.source.portal.monitor") : width > 0 ? Texts.tr("ddbeam.source.monitor", index + 1, width, height)
                    : Texts.tr("ddbeam.source.monitor.plain", index + 1);
            case CAMERA -> Texts.lit(title);
            case WINDOW -> portal() ? Texts.tr("ddbeam.source.portal.window") : Texts.lit(name);
            case TEST -> Texts.tr("ddbeam.source.test");
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
                // the mode picked in the dialog (size, frame rate, format), else cameraSize from the config
                List<String> mode = CameraModes.inputArgs(this, Sources.os(), fps);
                List<String> size = !mode.isEmpty() || config.cameraSize.isBlank() ? List.of() : List.of("-video_size", config.cameraSize);
                if (windows) {
                    args.addAll(List.of("-f", "dshow", "-rtbufsize", "64M"));
                    args.addAll(mode);
                    args.addAll(size);
                    args.addAll(List.of("-i", "video=" + name));
                } else if (mac) {
                    args.addAll(List.of("-f", "avfoundation"));
                    if (mode.isEmpty()) args.addAll(List.of("-framerate", "30"));
                    args.addAll(mode);
                    args.addAll(size);
                    args.addAll(List.of("-i", index + ":none"));
                } else {
                    args.addAll(List.of("-f", "v4l2"));
                    args.addAll(mode);
                    args.addAll(size);
                    args.addAll(List.of("-i", name));
                }
            }
            case WINDOW -> {
                if (handle != 0 && Ffmpeg.hasFilter("gfxcapture")) {
                    // Windows Graphics Capture: sees what GPU-drawn windows (browsers, most apps) show, unlike GDI,
                    // and follows the window by its handle while its title changes
                    args.addAll(List.of("-f", "lavfi", "-i", "gfxcapture=hwnd=" + handle + ":max_framerate=" + rate
                            + ":capture_cursor=" + (config.showCursor ? 1 : 0) + ":resize_mode=scale_aspect,hwdownload,format=bgra"));
                } else {
                    args.addAll(List.of("-f", "gdigrab", "-framerate", rate, "-draw_mouse", config.showCursor ? "1" : "0",
                            "-i", "title=" + name));
                }
            }
            case TEST -> args.addAll(List.of("-re", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=" + rate));
        }
        return args;
    }
}
