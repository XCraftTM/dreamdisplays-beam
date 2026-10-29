package dev.xcrafttm.ddbeam.client.capture;

import dev.xcrafttm.ddbeam.DdBeam;
import dev.xcrafttm.ddbeam.Texts;
import dev.xcrafttm.ddbeam.client.BeamConfig;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The picture modes a camera offers (size, frame rate, format), as FFmpeg lists them, and the one chosen for it.
 * Many webcams reach their full size and frame rate only in one format (1080p at 60 FPS as MJPEG, but 5 FPS
 * uncompressed), and without a choice the driver often picks the slow one.
 * <ul>
 *   <li>Windows: DirectShow's {@code -list_options}: size, frame rate range and pixel format or codec.</li>
 *   <li>Linux: V4L2's {@code -list_formats all}: sizes per format, no frame rates (the cast's frame rate is asked
 *       for, the driver takes the nearest).</li>
 *   <li>macOS: AVFoundation lists the supported modes when asked for a size the camera does not have.</li>
 * </ul>
 */
public final class CameraModes {
    /** {@code pixel_format=yuyv422  min s=1920x1080 fps=5 max s=1920x1080 fps=60.0002}, or {@code vcodec=mjpeg ...}. */
    private static final Pattern DSHOW_MODE = Pattern.compile(
            "(pixel_format|vcodec)=(\\S+)\\s+min s=\\d+x\\d+ fps=[\\d.]+ max s=(\\d+)x(\\d+) fps=([\\d.]+)");
    /** {@code 1920x1080@[1.000000 30.000000]fps}. */
    private static final Pattern AVF_MODE = Pattern.compile("(\\d+)x(\\d+)@\\[[\\d.]+\\s+([\\d.]+)]fps");
    private static final Pattern SIZE = Pattern.compile("^(\\d+)x(\\d+)$");
    /** Uncompressed formats before compressed ones of the same size and rate: less work, no decoding. */
    private static final List<String> PREFERRED = List.of("nv12", "yuyv422", "uyvy422", "yuv420p", "mjpeg");

    /**
     * One mode.
     *
     * @param fps        as FFmpeg listed it ("60.0002"), empty where the list has no frame rates (Linux)
     * @param format     FFmpeg's pixel format or codec name
     * @param compressed a codec (DirectShow {@code -vcodec}) rather than a pixel format
     */
    public record Mode(int width, int height, String fps, String format, boolean compressed) {
        String id() {
            return (compressed ? "codec:" : "pixel:") + format + " " + width + "x" + height + " " + (fps.isEmpty() ? "-" : fps);
        }

        static Mode parse(String id) {
            try {
                String[] parts = id.split(" ");
                String[] kindFormat = parts[0].split(":", 2);
                String[] size = parts[1].split("x");
                return new Mode(Integer.parseInt(size[0]), Integer.parseInt(size[1]), parts[2].equals("-") ? "" : parts[2],
                        kindFormat[1], kindFormat[0].equals("codec"));
            } catch (RuntimeException e) {
                return null;
            }
        }

        double rate() {
            try {
                return fps.isEmpty() ? 0 : Double.parseDouble(fps);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /** "1920 × 1080 · 60 FPS · MJPEG". */
        public Component label() {
            String format = this.format.toUpperCase(Locale.ROOT);
            if (fps.isEmpty()) return Texts.tr("ddbeam.dialog.camera_mode.value.nofps", width, height, format);
            double r = rate();
            String shown = Math.abs(r - Math.round(r)) < 0.01 ? String.valueOf(Math.round(r)) : String.format(Locale.ROOT, "%.2f", r);
            return Texts.tr("ddbeam.dialog.camera_mode.value", width, height, shown, format);
        }
    }

    private static final Map<String, CompletableFuture<List<Mode>>> MODES = new ConcurrentHashMap<>();

    private CameraModes() {
    }

    /** The camera's modes, best first; null while FFmpeg is still being asked (started by the first call). */
    public static List<Mode> get(CaptureSource camera) {
        return MODES.computeIfAbsent(camera.name(), name -> CompletableFuture.supplyAsync(() -> list(camera))).getNow(null);
    }

    /** The mode chosen for this camera, null: the camera's own default. */
    public static Mode chosen(CaptureSource camera) {
        String id = BeamConfig.get().cameraModes.get(camera.name());
        return id == null ? null : Mode.parse(id);
    }

    public static void choose(CaptureSource camera, Mode mode) {
        BeamConfig config = BeamConfig.get();
        if (mode == null) config.cameraModes.remove(camera.name());
        else config.cameraModes.put(camera.name(), mode.id());
        BeamConfig.save();
    }

    /** FFmpeg input options that open the camera in its chosen mode (before {@code -i}); empty without one. */
    static List<String> inputArgs(CaptureSource camera, String os, int castFps) {
        Mode mode = chosen(camera);
        if (mode == null) return List.of();
        List<String> args = new ArrayList<>();
        switch (os) {
            case "windows" -> {
                args.addAll(List.of(mode.compressed() ? "-vcodec" : "-pixel_format", mode.format()));
                args.addAll(List.of("-video_size", mode.width() + "x" + mode.height()));
                if (!mode.fps().isEmpty()) args.addAll(List.of("-framerate", mode.fps()));
            }
            case "mac" -> {
                args.addAll(List.of("-video_size", mode.width() + "x" + mode.height()));
                if (!mode.fps().isEmpty()) args.addAll(List.of("-framerate", mode.fps()));
            }
            default -> {
                args.addAll(List.of("-input_format", mode.format(), "-video_size", mode.width() + "x" + mode.height()));
                args.addAll(List.of("-framerate", String.valueOf(castFps)));
            }
        }
        return args;
    }

    private static List<Mode> list(CaptureSource camera) {
        String os = Sources.os();
        try {
            List<Mode> modes = switch (os) {
                case "windows" -> parseDshow(Ffmpeg.run(List.of("-f", "dshow", "-list_options", "true", "-i", "video=" + camera.name()), 8000));
                case "mac" -> parseAvfoundation(Ffmpeg.run(List.of("-f", "avfoundation", "-video_size", "1x1", "-i", camera.index() + ":none"), 8000));
                default -> parseV4l2(Ffmpeg.run(List.of("-f", "v4l2", "-list_formats", "all", "-i", camera.name()), 8000));
            };
            DdBeam.LOGGER.info("Camera {}: {} modes", camera.title(), modes.size());
            return modes;
        } catch (IOException e) {
            DdBeam.LOGGER.warn("Could not list the modes of camera {}: {}", camera.title(), e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return List.of();
    }

    static List<Mode> parseDshow(String out) {
        // one mode per size: the fastest (the cast's own frame rate can still be lower), uncompressed on a tie
        Map<String, Mode> best = new LinkedHashMap<>();
        Matcher m = DSHOW_MODE.matcher(out);
        while (m.find()) {
            Mode mode = new Mode(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), m.group(5), m.group(2), m.group(1).equals("vcodec"));
            String key = mode.width() + "x" + mode.height();
            Mode old = best.get(key);
            long rate = Math.round(mode.rate()), oldRate = old == null ? -1 : Math.round(old.rate());
            if (rate > oldRate || rate == oldRate && rank(mode.format()) < rank(old.format())) best.put(key, mode);
        }
        return sorted(best.values());
    }

    static List<Mode> parseAvfoundation(String out) {
        Map<String, Mode> modes = new LinkedHashMap<>();
        Matcher m = AVF_MODE.matcher(out);
        while (m.find()) {
            Mode mode = new Mode(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3), "", false);
            // the fastest per size
            Mode old = modes.get(mode.width() + "x" + mode.height());
            if (old == null || mode.rate() > old.rate()) modes.put(mode.width() + "x" + mode.height(), mode);
        }
        return sorted(modes.values());
    }

    /** {@code [video4linux2,v4l2 @ 0x..] Compressed:       mjpeg :          Motion-JPEG : 1920x1080 1280x720}. */
    static List<Mode> parseV4l2(String out) {
        Map<String, Mode> best = new LinkedHashMap<>();
        for (String line : out.split("\\R")) {
            if (!line.contains("Raw") && !line.contains("Compressed")) continue;
            String[] parts = line.split(" : ");
            if (parts.length < 4 || parts[2].contains("Emulated")) continue;
            String format = parts[1].trim();
            for (String size : parts[parts.length - 1].trim().split("\\s+")) {
                Matcher s = SIZE.matcher(size);
                if (!s.matches()) continue;
                Mode mode = new Mode(Integer.parseInt(s.group(1)), Integer.parseInt(s.group(2)), "", format, false);
                // one per size: MJPEG where offered (no frame rates in this list, and large uncompressed
                // pictures are usually the slow ones)
                String key = mode.width() + "x" + mode.height();
                Mode old = best.get(key);
                if (old == null || format.equals("mjpeg") && !old.format().equals("mjpeg")) best.put(key, mode);
            }
        }
        return sorted(best.values());
    }

    private static int rank(String format) {
        int i = PREFERRED.indexOf(format);
        return i < 0 ? PREFERRED.size() : i;
    }

    private static List<Mode> sorted(java.util.Collection<Mode> modes) {
        List<Mode> list = new ArrayList<>(modes);
        list.sort(Comparator.comparingInt((Mode mode) -> -mode.width() * mode.height())
                .thenComparingDouble(mode -> -mode.rate())
                .thenComparingInt(mode -> rank(mode.format())));
        return list;
    }
}
