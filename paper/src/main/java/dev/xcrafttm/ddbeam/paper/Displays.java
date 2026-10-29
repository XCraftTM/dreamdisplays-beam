package dev.xcrafttm.ddbeam.paper;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Dream Displays' displays, through its server API ({@code com.dreamdisplays.api.server.DreamDisplaysServer}). It
 * is a Paper plugin with its own class loader, so the API is reached through that loader by reflection.
 */
final class Displays {
    /** Where a display is and who owns it. {@code world} is the Bukkit world name, x/y/z its center. */
    record Info(String world, double x, double y, double z, UUID owner) {
    }

    private Method getDisplays, get, world, x, y, z, owner;
    private boolean missing;

    /** Null when Dream Displays is not there or does not know the display. */
    Info find(UUID id) {
        if (missing) return null;
        try {
            if (getDisplays == null) {
                Plugin dd = Bukkit.getPluginManager().getPlugin("DreamDisplays");
                if (dd == null) return null;
                ClassLoader loader = dd.getClass().getClassLoader();
                Class<?> api = Class.forName("com.dreamdisplays.api.server.DreamDisplaysServer", true, loader);
                Class<?> displays = Class.forName("com.dreamdisplays.api.server.ServerDisplays", true, loader);
                Class<?> display = Class.forName("com.dreamdisplays.api.server.ServerDisplay", true, loader);
                getDisplays = api.getMethod("getDisplays");
                get = displays.getMethod("get", UUID.class);
                world = display.getMethod("getWorld");
                x = display.getMethod("getX");
                y = display.getMethod("getY");
                z = display.getMethod("getZ");
                owner = display.getMethod("getOwnerId");
            }
            Object d = get.invoke(getDisplays.invoke(null), id);
            if (d == null) return null;
            return new Info((String) world.invoke(d), (double) x.invoke(d), (double) y.invoke(d), (double) z.invoke(d),
                    (UUID) owner.invoke(d));
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            missing = true;
            Bukkit.getLogger().warning("[DDBeam] Dream Displays' server API is not there (" + e + "); casts are not relayed");
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
