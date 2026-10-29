package dev.xcrafttm.ddbeam.client.capture;

import dev.xcrafttm.ddbeam.DdBeam;
//#if MC >= 26.3
import org.lwjgl.sdl.SDLStdinc;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.system.MemoryStack;
//#else
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;
//#endif

import java.io.File;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What can be cast on this computer: its monitors, cameras and audio inputs.
 * <ul>
 *   <li>Windows: monitors from SDL (gdigrab / ddagrab), cameras and audio from DirectShow.</li>
 *   <li>Linux: monitors from SDL (x11grab: X11 sessions only, Wayland has no FFmpeg grabber), cameras from
 *       V4L2 (named from sysfs), audio inputs from PulseAudio / PipeWire.</li>
 *   <li>macOS: screens, cameras and audio inputs from AVFoundation (needs the screen recording and camera
 *       permissions for the launcher).</li>
 * </ul>
 */
public final class Sources {
    private static final Pattern DSHOW_DEVICE = Pattern.compile("\"([^\"]+)\"\\s+\\((video|audio)\\)");
    private static final Pattern AVF_DEVICE = Pattern.compile("\\[(\\d+)]\\s+(.+)$");
    /** {@code ffmpeg -sources pulse}: "* name [description]" or "  name [description]". */
    private static final Pattern PULSE_SOURCE = Pattern.compile("^\\s*\\*?\\s*(\\S+)\\s+\\[(.+)]\\s*$");

    /** An audio input: what FFmpeg opens it by, and the name people know it by. */
    public record AudioDevice(String id, String name) {
    }

    private static volatile List<CaptureSource> cameras;
    private static volatile List<CaptureSource> macScreens = List.of();
    private static volatile List<AudioDevice> audioDevices = List.of();

    private Sources() {
    }

    public static String os() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "windows" : os.contains("mac") ? "mac" : "linux";
    }

    /**
     * Linux under Wayland: screens can only be captured through the desktop portal (x11grab would only see the
     * XWayland windows), so there is one source whose screen the desktop's picker chooses.
     */
    public static boolean wayland() {
        return os().equals("linux") && ("wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE"))
                || System.getenv("WAYLAND_DISPLAY") != null);
    }

    /** The monitors. Call on the render thread (SDL, or GLFW before 26.3). */
    public static List<CaptureSource> monitors() {
        if (wayland()) return List.of(CaptureSource.portal(CaptureSource.Kind.MONITOR));
        List<CaptureSource> sdl = windowSystemMonitors();
        if (!os().equals("mac")) return sdl;
        // AVFoundation numbers its screens itself; take the sizes from SDL when both see the same count
        List<CaptureSource> out = new ArrayList<>();
        List<CaptureSource> screens = macScreens;
        for (int i = 0; i < screens.size(); i++) {
            CaptureSource s = screens.get(i);
            CaptureSource size = sdl.size() == screens.size() ? sdl.get(i) : null;
            out.add(new CaptureSource(CaptureSource.Kind.MONITOR, i, s.name(), 0, 0,
                    size != null ? size.width() : 0, size != null ? size.height() : 0));
        }
        return out;
    }

    private static List<CaptureSource> windowSystemMonitors() {
        List<CaptureSource> out = new ArrayList<>();
        try {
            //#if MC >= 26.3
            IntBuffer ids = SDLVideo.SDL_GetDisplays();
            if (ids != null) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    SDL_Rect rect = SDL_Rect.malloc(stack);
                    for (int i = 0; i < ids.remaining(); i++) {
                        if (!SDLVideo.SDL_GetDisplayBounds(ids.get(i), rect)) continue;
                        out.add(new CaptureSource(CaptureSource.Kind.MONITOR, i, "", rect.x(), rect.y(), rect.w() & ~1, rect.h() & ~1));
                    }
                } finally {
                    SDLStdinc.SDL_free(ids);
                }
            }
            //#else
            PointerBuffer monitors = GLFW.glfwGetMonitors();
            if (monitors != null) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer x = stack.mallocInt(1);
                    IntBuffer y = stack.mallocInt(1);
                    for (int i = 0; i < monitors.remaining(); i++) {
                        long monitor = monitors.get(i);
                        GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
                        if (mode == null) continue;
                        GLFW.glfwGetMonitorPos(monitor, x, y);
                        out.add(new CaptureSource(CaptureSource.Kind.MONITOR, i, "", x.get(0), y.get(0), mode.width() & ~1, mode.height() & ~1));
                    }
                }
            }
            //#endif
        } catch (RuntimeException | LinkageError e) {
            DdBeam.LOGGER.warn("Could not list the monitors: {}", e.toString());
        }
        return out;
    }

    /** Application windows: Windows lists them, Wayland's picker chooses one, elsewhere there are none. */
    public static List<CaptureSource> windows() {
        if (wayland()) return List.of(CaptureSource.portal(CaptureSource.Kind.WINDOW));
        return WindowList.list();
    }

    /** The cameras found by the last {@link #refresh()}, null before it finished once. */
    public static List<CaptureSource> cameras() {
        return cameras;
    }

    public static List<AudioDevice> audioDevices() {
        return audioDevices;
    }

    /**
     * The audio input that belongs to a camera or capture card, by name: Windows names it like the camera
     * ("Game Capture 4K60 Pro MK.2 Audio", "Mikrofon (USB Camera)"), PulseAudio and AVFoundation the same way.
     */
    public static AudioDevice audioOf(CaptureSource camera) {
        String cam = camera.label().getString().toLowerCase(Locale.ROOT);
        String name = camera.name().toLowerCase(Locale.ROOT);
        if (os().equals("linux")) name = cam;
        for (AudioDevice device : audioDevices) {
            String n = device.name().toLowerCase(Locale.ROOT);
            // monitors of outputs are not microphones
            if (device.id().endsWith(".monitor")) continue;
            if (!name.isBlank() && (n.contains(name) || name.contains(n))) return device;
        }
        return null;
    }

    public static AudioDevice audioByName(String wanted) {
        for (AudioDevice device : audioDevices) {
            if (device.name().equalsIgnoreCase(wanted) || device.id().equalsIgnoreCase(wanted)) return device;
        }
        return null;
    }

    /** Asks FFmpeg (and on Linux sysfs) for the cameras, audio inputs and macOS screens, in the background. */
    public static CompletableFuture<List<CaptureSource>> refresh() {
        return CompletableFuture.supplyAsync(() -> {
            List<CaptureSource> found = new ArrayList<>();
            List<AudioDevice> audio = new ArrayList<>();
            try {
                switch (os()) {
                    case "windows" -> {
                        String out = Ffmpeg.run(List.of("-list_devices", "true", "-f", "dshow", "-i", "dummy"), 8000);
                        Matcher m = DSHOW_DEVICE.matcher(out);
                        while (m.find()) {
                            if (m.group(2).equals("video")) found.add(new CaptureSource(CaptureSource.Kind.CAMERA, found.size(), m.group(1), 0, 0, 0, 0));
                            else audio.add(new AudioDevice(m.group(1), m.group(1)));
                        }
                    }
                    case "mac" -> {
                        String out = Ffmpeg.run(List.of("-f", "avfoundation", "-list_devices", "true", "-i", ""), 8000);
                        List<CaptureSource> screens = new ArrayList<>();
                        String section = "";
                        for (String line : out.split("\\R")) {
                            if (line.contains("AVFoundation video devices")) section = "video";
                            else if (line.contains("AVFoundation audio devices")) section = "audio";
                            Matcher m = AVF_DEVICE.matcher(line);
                            if (section.isEmpty() || !m.find()) continue;
                            String index = m.group(1), name = m.group(2).trim();
                            if (section.equals("audio")) {
                                audio.add(new AudioDevice(index, name));
                            } else if (name.startsWith("Capture screen")) {
                                screens.add(new CaptureSource(CaptureSource.Kind.MONITOR, screens.size(), index, 0, 0, 0, 0));
                            } else {
                                found.add(new CaptureSource(CaptureSource.Kind.CAMERA, Integer.parseInt(index), name, 0, 0, 0, 0));
                            }
                        }
                        macScreens = screens;
                    }
                    default -> {
                        found.addAll(v4l2Cameras());
                        String out = Ffmpeg.run(List.of("-sources", "pulse"), 8000);
                        for (String line : out.split("\\R")) {
                            Matcher m = PULSE_SOURCE.matcher(line);
                            if (m.find()) audio.add(new AudioDevice(m.group(1), m.group(2).trim()));
                        }
                    }
                }
            } catch (Exception e) {
                DdBeam.LOGGER.warn("Could not list the cameras: {}", e.toString());
            }
            audioDevices = audio;
            cameras = found;
            return found;
        });
    }

    /**
     * V4L2 capture nodes. A camera usually has several /dev/video nodes (the others carry metadata); the capture
     * one has index 0 in sysfs.
     */
    private static List<CaptureSource> v4l2Cameras() {
        List<CaptureSource> out = new ArrayList<>();
        File[] nodes = new File("/sys/class/video4linux").listFiles((dir, name) -> name.startsWith("video"));
        if (nodes == null) return out;
        Arrays.sort(nodes, (a, b) -> Integer.compare(number(a.getName()), number(b.getName())));
        for (File node : nodes) {
            String index = read(new File(node, "index"));
            if (index != null && !index.equals("0")) continue;
            String name = read(new File(node, "name"));
            String device = "/dev/" + node.getName();
            out.add(new CaptureSource(CaptureSource.Kind.CAMERA, out.size(), device, 0, 0, 0, 0, name != null ? name : device));
        }
        return out;
    }

    private static int number(String videoN) {
        try {
            return Integer.parseInt(videoN.substring(5));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String read(File file) {
        try {
            return Files.readString(file.toPath()).trim();
        } catch (IOException e) {
            return null;
        }
    }
}
