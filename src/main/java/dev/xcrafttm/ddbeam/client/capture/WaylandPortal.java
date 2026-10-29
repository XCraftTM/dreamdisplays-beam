package dev.xcrafttm.ddbeam.client.capture;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The Wayland capture helper ({@code ddbeam/wayland-cast.py} in the jar): written next to the config so python3
 * can run it, and where it keeps the portal's restore tokens (the picker then only shows the first time).
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
            default -> null;
        };
    }
}
