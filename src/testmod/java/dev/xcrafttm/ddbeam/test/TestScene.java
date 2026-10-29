package dev.xcrafttm.ddbeam.test;

import com.dreamdisplays.api.display.model.property.DisplayFacing;
import com.dreamdisplays.api.server.DreamDisplaysServer;
import com.dreamdisplays.api.server.ServerDisplay;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Builds a lit room with an 8 x 4 display on its back wall when the first player joins, and places every player
 * that joins in front of it (the caster in the middle, a viewer to the side).
 */
public final class TestScene implements ModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("ddbeam-test");

    private record Pending(ServerPlayer player, int ticks) {
    }

    private static final Deque<Pending> PENDING = new ArrayDeque<>();
    private static boolean built;

    @Override
    public void onInitialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> join(server, handler.player)));
        // the join moves the player once more after the event: place them again a bit later
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int n = PENDING.size();
            for (int i = 0; i < n; i++) {
                Pending p = PENDING.poll();
                if (p.ticks() < 40) {
                    PENDING.add(new Pending(p.player(), p.ticks() + 1));
                    continue;
                }
                place(server, p.player());
                if (!p.player().getScoreboardName().equals("Viewer")) {
                    DreamDisplaysServer.onReady(() -> server.execute(() -> createDisplay(p.player())));
                }
            }
        });
    }

    static void run(MinecraftServer server, String command) {
        //#if MC >= 1.19
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(new CommandSource() {
        //#else
        server.getCommands().performCommand(server.createCommandSourceStack().withSource(new CommandSource() {
        //#endif
            //#if MC >= 1.19
            @Override
            public void sendSystemMessage(Component message) {
                LOG.info("/{}: {}", command, message.getString());
            }
            //#else
            @Override
            public void sendMessage(Component message, java.util.UUID sender) {
                LOG.info("/{}: {}", command, message.getString());
            }
            //#endif

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        }), command);
    }

    private static void join(MinecraftServer server, ServerPlayer player) {
        if (!built) {
            built = true;
            String[] commands = {
                    "fill -10 -61 -2 10 -50 20 minecraft:smooth_stone hollow",
                    "fill -9 -61 -1 9 -61 19 minecraft:oak_planks",
                    "fill -9 -51 -1 9 -51 19 minecraft:smooth_stone",
                    "fill -6 -51 2 6 -51 16 minecraft:sea_lantern",
                    "fill -9 -60 -1 9 -55 -1 minecraft:black_concrete",
                    "fill -3 -60 8 3 -60 9 minecraft:red_wool",
                    "time set noon",
                    "gamerule doDaylightCycle false",
            };
            for (String command : commands) run(server, command);
        }
        run(server, "gamemode creative " + player.getScoreboardName());
        place(server, player);
        PENDING.add(new Pending(player, 0));
    }

    private static void place(MinecraftServer server, ServerPlayer player) {
        boolean viewer = player.getScoreboardName().equals("Viewer");
        run(server, "tp " + player.getScoreboardName() + (viewer ? " -3.5 -60 11.5 200 2" : " 0.5 -60 11.5 180 2"));
    }

    private static java.util.List<ServerDisplay> toList(Iterable<ServerDisplay> displays) {
        java.util.List<ServerDisplay> list = new java.util.ArrayList<>();
        for (ServerDisplay display : displays) list.add(display);
        return list;
    }

    private static void createDisplay(ServerPlayer player) {
        // -Dddbeam.test.depth=N: the display made anew, N blocks deep from the back wall into the room
        String depth = System.getProperty("ddbeam.test.depth");
        if (depth != null) {
            for (ServerDisplay display : toList(DreamDisplaysServer.getDisplays())) {
                LOG.info("Deleting display {}: {}", display.getId(), display.delete());
            }
        } else {
            for (ServerDisplay display : DreamDisplaysServer.getDisplays()) {
                LOG.info("Display {} already there", display.getId());
                return;
            }
        }
        int front = -1 + (depth == null ? 1 : Integer.parseInt(depth)) - 1;
        // 8 x 4 blocks of the back wall, facing into the room
        ServerDisplay display = DreamDisplaysServer.getDisplays().create(player.getUUID(), "minecraft:overworld",
                -4, -59, -1, 3, -56, front, DisplayFacing.SOUTH);
        LOG.info("Created display {}", display == null ? "-" : display.getId());
    }
}
