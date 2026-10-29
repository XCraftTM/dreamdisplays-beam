package dev.xcrafttm.ddbeam.paper.test;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * Dev only: the Paper counterpart of the mod's test scene. The first player to join gets a lit room with an 8 x 4
 * display on its back wall (made through Dream Displays' server API); everyone is placed in front of it.
 */
public final class TestScenePlugin extends JavaPlugin implements Listener {
    private boolean built;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> place(player), 40);
    }

    private void run(String command) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private void place(Player player) {
        World world = player.getWorld();
        if (!built) {
            built = true;
            String w = "execute in " + world.getKey() + " run ";
            for (String c : new String[]{
                    "fill -10 -61 -2 10 -50 20 minecraft:smooth_stone hollow",
                    "fill -9 -61 -1 9 -61 19 minecraft:oak_planks",
                    "fill -6 -51 2 6 -51 16 minecraft:sea_lantern",
                    "fill -9 -60 -1 9 -55 -1 minecraft:black_concrete",
                    "fill -3 -60 8 3 -60 9 minecraft:red_wool"}) {
                run(w + c);
            }
            run("time set noon");
            createDisplay(player, world);
        }
        player.setGameMode(GameMode.CREATIVE);
        boolean viewer = player.getName().equals("Viewer");
        player.teleport(new Location(world, viewer ? -3.5 : 0.5, -60, viewer ? 11.5 : 11.5, viewer ? 200 : 180, 2));
    }

    private void createDisplay(Player owner, World world) {
        try {
            Plugin dd = Bukkit.getPluginManager().getPlugin("DreamDisplays");
            ClassLoader loader = dd.getClass().getClassLoader();
            Class<?> api = Class.forName("com.dreamdisplays.api.server.DreamDisplaysServer", true, loader);
            Class<?> facing = Class.forName("com.dreamdisplays.api.display.model.property.DisplayFacing", true, loader);
            Object displays = api.getMethod("getDisplays").invoke(null);
            for (Object d : (Iterable<?>) displays) {
                getLogger().info("Display already there: " + d);
                return;
            }
            Object south = facing.getField("SOUTH").get(null);
            Object created = displays.getClass().getMethod("create", UUID.class, String.class, int.class, int.class, int.class,
                            int.class, int.class, int.class, facing)
                    .invoke(displays, owner.getUniqueId(), world.getName(), -4, -59, -1, 3, -56, -1, south);
            getLogger().info("Created display " + created);
        } catch (ReflectiveOperationException | RuntimeException e) {
            getLogger().severe("Could not create the test display: " + e);
        }
    }
}
