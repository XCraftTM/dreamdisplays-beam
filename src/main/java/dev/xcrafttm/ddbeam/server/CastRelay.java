package dev.xcrafttm.ddbeam.server;

import com.dreamdisplays.api.server.DreamDisplaysServer;
import com.dreamdisplays.api.server.ServerDisplay;
import dev.xcrafttm.ddbeam.DdBeam;
import dev.xcrafttm.ddbeam.net.BeamPayloads;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Passes casts on to the players near the display. The server never decodes a frame: it checks who may cast, limits
 * the rate and forwards the JPEG bytes as they came. All of it runs on the server thread (Fabric runs play payload
 * handlers there).
 */
public final class CastRelay {
    /** A cast whose caster sent nothing for this long is over (the caster's game stopped, or it lost the cast). */
    private static final long IDLE_NS = 10_000_000_000L;

    private static final class Session {
        final UUID display;
        final UUID caster;
        final String world;
        final double x, y, z;
        final Set<UUID> viewers = new HashSet<>();
        long lastFrameNs = System.nanoTime();
        long lastSentNs;
        long lastAudioNs;
        double budget;
        long budgetNs = System.nanoTime();

        Session(UUID display, UUID caster, String world, double x, double y, double z) {
            this.display = display;
            this.caster = caster;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private CastRelay() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(BeamPayloads.Start.TYPE, (payload, ctx) -> start(ctx.server(), ctx.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(BeamPayloads.Stop.TYPE, (payload, ctx) -> {
            Session session = SESSIONS.get(payload.display());
            if (session != null && session.caster.equals(ctx.player().getUUID())) end(ctx.server(), session);
        });
        ServerPlayNetworking.registerGlobalReceiver(BeamPayloads.Frame.TYPE, (payload, ctx) -> frame(ctx.server(), ctx.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(BeamPayloads.Audio.TYPE, (payload, ctx) -> audio(ctx.server(), ctx.player(), payload));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> server.execute(() -> {
            UUID player = handler.player.getUUID();
            for (Session session : sessions()) if (session.caster.equals(player)) end(server, session);
        }));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 != 0) return;
            long now = System.nanoTime();
            for (Session session : sessions()) if (now - session.lastFrameNs > IDLE_NS) end(server, session);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SESSIONS.clear());
    }

    private static Session[] sessions() {
        return SESSIONS.values().toArray(new Session[0]);
    }

    private static void notice(ServerPlayer player, UUID display, String key) {
        if (ServerPlayNetworking.canSend(player, BeamPayloads.Notice.TYPE)) {
            ServerPlayNetworking.send(player, new BeamPayloads.Notice(display, key));
        }
    }

    private static void start(MinecraftServer server, ServerPlayer player, BeamPayloads.Start payload) {
        ServerConfig config = ServerConfig.get();
        UUID id = payload.display();
        if (!config.relay) {
            notice(player, id, "relay_off");
            return;
        }
        ServerDisplay display;
        try {
            display = DreamDisplaysServer.getDisplays().get(id);
        } catch (RuntimeException | LinkageError e) {
            display = null;
        }
        if (display == null) {
            notice(player, id, "unknown_display");
            return;
        }
        if (!mayCast(server, player, display)) {
            notice(player, id, "not_allowed");
            return;
        }
        Session current = SESSIONS.get(id);
        if (current != null && !current.caster.equals(player.getUUID()) && server.getPlayerList().getPlayer(current.caster) != null) {
            notice(player, id, "busy");
            return;
        }
        // one cast per player: a new one ends the one before
        for (Session session : sessions()) {
            if (session.caster.equals(player.getUUID()) && !session.display.equals(id)) end(server, session);
        }
        if (current != null) end(server, current);
        SESSIONS.put(id, new Session(id, player.getUUID(), display.getWorld(),
                display.getX() + display.getWidth() / 2.0, display.getY() + display.getHeight() / 2.0, display.getZ()));
        DdBeam.LOGGER.info("{} casts onto display {} ({} x {})", player.getScoreboardName(), id, payload.width(), payload.height());
        notice(player, id, "shared");
    }

    private static boolean mayCast(MinecraftServer server, ServerPlayer player, ServerDisplay display) {
        boolean op = server.getPlayerList().isOp(player.nameAndId()) || server.isSingleplayerOwner(player.nameAndId());
        return switch (ServerConfig.get().whoCanCast) {
            case "everyone" -> true;
            case "operators" -> op;
            default -> op || display.getOwnerId().equals(player.getUUID());
        };
    }

    private static void frame(MinecraftServer server, ServerPlayer player, BeamPayloads.Frame payload) {
        Session session = SESSIONS.get(payload.display());
        if (session == null || !session.caster.equals(player.getUUID())) return;
        ServerConfig config = ServerConfig.get();
        long now = System.nanoTime();
        session.lastFrameNs = now;
        if (now - session.lastSentNs < 1_000_000_000L / Math.max(1, config.maxFps) * 9 / 10) return;
        // byte budget: refills at the configured rate, holds at most one second of it
        double rate = config.maxKilobytesPerSecond * 1024.0;
        session.budget = Math.min(rate, session.budget + rate * (now - session.budgetNs) / 1e9);
        session.budgetNs = now;
        int size = payload.jpeg().length;
        if (size > session.budget) return;
        session.budget -= size;
        session.lastSentNs = now;

        BeamPayloads.Frame out = new BeamPayloads.Frame(session.display, session.caster, payload.seq(), payload.jpeg());
        Set<UUID> reached = new HashSet<>();
        ServerLevel level = levelOf(server, session.world);
        if (level != null) {
            double range2 = config.viewDistance * config.viewDistance;
            for (ServerPlayer viewer : level.players()) {
                if (viewer == player || viewer.distanceToSqr(session.x, session.y, session.z) > range2) continue;
                if (!ServerPlayNetworking.canSend(viewer, BeamPayloads.Frame.TYPE)) continue;
                ServerPlayNetworking.send(viewer, out);
                reached.add(viewer.getUUID());
            }
        }
        // players who walked away or changed worlds see the display's own picture again
        for (Iterator<UUID> it = session.viewers.iterator(); it.hasNext(); ) {
            UUID viewer = it.next();
            if (reached.contains(viewer)) continue;
            it.remove();
            sendStop(server, viewer, session.display);
        }
        session.viewers.addAll(reached);
    }

    /** Audio goes to whoever gets the cast's frames (frames keep coming while a picture stands still). */
    private static void audio(MinecraftServer server, ServerPlayer player, BeamPayloads.Audio payload) {
        Session session = SESSIONS.get(payload.display());
        if (session == null || !session.caster.equals(player.getUUID())) return;
        long now = System.nanoTime();
        // about 25 packets a second are sent; more is not audio
        if (now - session.lastAudioNs < 10_000_000L) return;
        session.lastAudioNs = now;
        BeamPayloads.Audio out = new BeamPayloads.Audio(session.display, session.caster, payload.seq(), payload.data());
        for (UUID id : session.viewers) {
            ServerPlayer viewer = server.getPlayerList().getPlayer(id);
            if (viewer != null && ServerPlayNetworking.canSend(viewer, BeamPayloads.Audio.TYPE)) ServerPlayNetworking.send(viewer, out);
        }
    }

    private static ServerLevel levelOf(MinecraftServer server, String world) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().identifier().toString().equals(world)) return level;
        }
        return null;
    }

    private static void sendStop(MinecraftServer server, UUID viewer, UUID display) {
        ServerPlayer player = server.getPlayerList().getPlayer(viewer);
        if (player != null && ServerPlayNetworking.canSend(player, BeamPayloads.Stop.TYPE)) {
            ServerPlayNetworking.send(player, new BeamPayloads.Stop(display));
        }
    }

    private static void end(MinecraftServer server, Session session) {
        if (SESSIONS.get(session.display) != session) return;
        SESSIONS.remove(session.display);
        for (UUID viewer : session.viewers) sendStop(server, viewer, session.display);
        DdBeam.LOGGER.info("Cast onto display {} ended", session.display);
    }
}
