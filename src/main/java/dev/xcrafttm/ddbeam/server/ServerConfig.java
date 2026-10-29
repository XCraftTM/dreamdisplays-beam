package dev.xcrafttm.ddbeam.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.xcrafttm.ddbeam.DdBeam;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Server side settings, {@code config/ddbeam-server.json}. */
public final class ServerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ServerConfig instance = new ServerConfig();

    /** Relay casts to other players at all. */
    public boolean relay = true;
    /** Who may cast onto a display: "everyone", "owner" (the display's owner and operators) or "operators". */
    public String whoCanCast = "owner";
    /** Players within this many blocks of a display receive its cast. */
    public double viewDistance = 96;
    /** Per caster: bandwidth that is relayed to each viewer, in kilobytes per second; frames above it are dropped. */
    public int maxKilobytesPerSecond = 2048;
    /** Per caster: frames per second that are relayed at most. */
    public int maxFps = 30;

    public static ServerConfig get() {
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddbeam-server.json");
    }

    public static void load() {
        Path path = path();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                ServerConfig read = GSON.fromJson(reader, ServerConfig.class);
                if (read != null) instance = read;
            } catch (IOException | RuntimeException e) {
                DdBeam.LOGGER.warn("Could not read {}, using the defaults: {}", path, e.toString());
            }
        }
        // write it back, so new settings show up in old files
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
