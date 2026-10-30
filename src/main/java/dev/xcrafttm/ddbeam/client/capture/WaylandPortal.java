package dev.xcrafttm.ddbeam.client.capture;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The Wayland capture helpers, written next to the config, where they also keep the portal's restore tokens (the
 * picker then only shows the first time):
 * <ul>
 *   <li>{@code ddbeam-wayland}, native (Rust, from {@code native/wayland-capture}; in the jar for x86-64 and ARM
 *       Linux): the portal and PipeWire, raw frames into Beam's usual FFmpeg pipeline, so the cast streams as
 *       H.264 / HEVC like on X11 and Windows. Needs nothing but PipeWire.</li>
 *   <li>{@code wayland-cast.py}, for other processors: python3 with PyGObject and GStreamer's PipeWire plugin, JPEG
 *       frames only.</li>
 * </ul>
 */
public final class WaylandPortal {
    private WaylandPortal() {
    }

    private static Path dir() throws IOException {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("ddbeam");
        Files.createDirectories(dir);
        return dir;
    }

    public static Path script() throws IOException {
        Path script = dir().resolve("wayland-cast.py");
        try (InputStream in = WaylandPortal.class.getResourceAsStream("/ddbeam/wayland-cast.py")) {
            if (in == null) throw new IOException("wayland-cast.py is missing from the mod");
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
        }
        return script;
    }

    /** The native helper for this processor, written out if needed; null when the mod has none for it. */
    public static Path helper() throws IOException {
        String arch = switch (System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT)) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> null;
        };
        if (arch == null) return null;
        byte[] bytes;
        try (InputStream in = WaylandPortal.class.getResourceAsStream("/ddbeam/native/linux-" + arch + "/ddbeam-wayland")) {
            if (in == null) return null;
            bytes = in.readAllBytes();
        }
        Path helper = dir().resolve("ddbeam-wayland");
        // written again only when it changed (a running copy could not be replaced)
        if (!Files.exists(helper) || !java.util.Arrays.equals(Files.readAllBytes(helper), bytes)) {
            Path part = dir().resolve("ddbeam-wayland.part");
            Files.write(part, bytes);
            Files.move(part, helper, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!helper.toFile().canExecute() && !helper.toFile().setExecutable(true)) {
            throw new IOException("cannot make " + helper + " executable");
        }
        return helper;
    }

    public static Path tokenFile(CaptureSource.Kind kind) throws IOException {
        return dir().resolve("portal-" + kind.name().toLowerCase(java.util.Locale.ROOT) + ".token");
    }

    /** What a helper exit code means, as a translation key (null: no known reason). */
    public static String reason(int exitCode) {
        return switch (exitCode) {
            case 2 -> "ddbeam.error.portal.cancelled";
            case 3 -> "ddbeam.error.portal.gi";
            case 4 -> "ddbeam.error.portal.gstreamer";
            case 5 -> "ddbeam.error.portal.failed";
            case 6 -> "ddbeam.error.portal.pipewire";
            default -> null;
        };
    }
}
