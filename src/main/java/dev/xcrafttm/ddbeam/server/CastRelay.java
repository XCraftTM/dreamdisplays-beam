package dev.xcrafttm.ddbeam.server;

import com.dreamdisplays.api.server.DreamDisplaysServer;
import com.dreamdisplays.api.server.ServerDisplay;
import dev.xcrafttm.ddbeam.DdBeam;
import dev.xcrafttm.ddbeam.net.BeamPayloads;
import dev.xcrafttm.ddbeam.net.Congestion;
import dev.xcrafttm.ddbeam.net.TsUnits;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import dev.xcrafttm.ddbeam.net.BeamNet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
 * the rate and forwards the data as it came. All of it runs on the server thread.
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
        long dropped;
        int keepSeq = -1;
        double budget;
        long budgetNs = System.nanoTime();
        /** The cast within the byte budget, and each viewer's connection: whole frames only, see TsUnits. */
        final TsUnits.Gate budgetGate = new TsUnits.Gate();
        final Map<UUID, TsUnits.Gate> viewerGates = new HashMap<>();

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
        BeamNet.receive(BeamPayloads.START, safe((player, data) -> start(serverOf(player), player, BeamPayloads.Start.decode(data))));
        BeamNet.receive(BeamPayloads.STOP, safe((player, data) -> {
            Session session = SESSIONS.get(BeamPayloads.Stop.decode(data).display());
            if (session != null && session.caster.equals(player.getUUID())) end(serverOf(player), session);
        }));
        BeamNet.receive(BeamPayloads.FRAME, safe((player, data) -> frame(serverOf(player), player, BeamPayloads.Frame.decode(data))));
        BeamNet.receive(BeamPayloads.STREAM, safe((player, data) -> stream(serverOf(player), player, BeamPayloads.Stream.decode(data))));
        BeamNet.receive(BeamPayloads.AUDIO, safe((player, data) -> audio(serverOf(player), player, BeamPayloads.Audio.decode(data))));
        // a caster's delay probe goes straight back
        BeamNet.receive(BeamPayloads.PROBE, safe((player, data) -> {
            if (data.length == 8 && BeamNet.canSend(player, BeamPayloads.PROBE)) BeamNet.send(player, BeamPayloads.PROBE, data);
        }));
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

    /** A broken packet from a client is ignored. */
    private static BeamNet.ServerHandler safe(BeamNet.ServerHandler handler) {
        return (player, data) -> {
            try {
                handler.handle(player, data);
            } catch (IllegalArgumentException ignored) {
            }
        };
    }

    private static MinecraftServer serverOf(ServerPlayer player) {
        //#if MC >= 1.21.9
        return player.level().getServer();
        //#else
        return player.getServer();
        //#endif
    }

    private static void send(ServerPlayer player, String channel, byte[] data) {
        if (BeamNet.canSend(player, channel)) BeamNet.send(player, channel, data);
    }

    private static Session[] sessions() {
        return SESSIONS.values().toArray(new Session[0]);
    }

    private static void notice(ServerPlayer player, UUID display, String key) {
        send(player, BeamPayloads.NOTICE, new BeamPayloads.Notice(display, key).encode());
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
        //#if MC >= 1.21.9
        var profile = player.nameAndId();
        //#else
        var profile = player.getGameProfile();
        //#endif
        boolean op = server.getPlayerList().isOp(profile) || server.isSingleplayerOwner(profile);
        return switch (ServerConfig.get().whoCanCast) {
            case "everyone" -> true;
            case "operators" -> op;
            default -> op || display.getOwnerId().equals(player.getUUID());
        };
    }

    /** A part of a JPEG frame: whole frames are kept or dropped (frame rate limit, byte budget). */
    private static void frame(MinecraftServer server, ServerPlayer player, BeamPayloads.Frame payload) {
        Session session = SESSIONS.get(payload.display());
        if (session == null || !session.caster.equals(player.getUUID())) return;
        ServerConfig config = ServerConfig.get();
        long now = System.nanoTime();
        session.lastFrameNs = now;
        if (payload.part() == 0) {
            // byte budget: refills at the configured rate, holds at most one second of it
            double rate = config.maxKilobytesPerSecond * 1024.0;
            session.budget = Math.min(rate, session.budget + rate * (now - session.budgetNs) / 1e9);
            session.budgetNs = now;
            double frameBytes = (double) payload.data().length * payload.parts();
            boolean tooSoon = now - session.lastSentNs < 1_000_000_000L / Math.max(1, config.maxFps) * 9 / 10;
            session.keepSeq = tooSoon || frameBytes > session.budget ? -1 : payload.seq();
            if (session.keepSeq < 0) return;
            session.budget -= frameBytes;
            session.lastSentNs = now;
        } else if (payload.seq() != session.keepSeq) {
            return;
        }
        deliver(server, player, session, BeamPayloads.FRAME, new BeamPayloads.Frame(session.display, session.caster, payload.seq(),
                payload.part(), payload.parts(), payload.data()).encode());
    }

    /**
     * A piece of the cast's video. Every frame builds on the ones before, so a frame cannot simply be left out: over
     * the byte budget, or for a viewer whose connection is too far behind, whole frames are left out up to the next key
     * frame (see TsUnits), and the viewers see the last whole picture a little longer instead of a broken one.
     */
    private static void stream(MinecraftServer server, ServerPlayer player, BeamPayloads.Stream payload) {
        Session session = SESSIONS.get(payload.display());
        if (session == null || !session.caster.equals(player.getUUID())) return;
        long now = System.nanoTime();
        session.lastFrameNs = now;
        double rate = ServerConfig.get().maxKilobytesPerSecond * 1024.0;
        session.budget = Math.min(rate, session.budget + rate * (now - session.budgetNs) / 1e9);
        session.budgetNs = now;
        byte[] data = payload.data();
        boolean unitStart = TsUnits.startsUnit(data), key = unitStart && TsUnits.holdsKey(data);
        if (!session.budgetGate.pass(unitStart, key, data.length > session.budget, data.length <= session.budget)) {
            session.dropped++;
            return;
        }
        session.budget = Math.max(0, session.budget - data.length);
        byte[] out = new BeamPayloads.Stream(session.display, session.caster, payload.seq(), payload.width(), payload.height(), data).encode();
        deliver(server, player, session, BeamPayloads.STREAM, out, viewer -> {
            long behind = Congestion.pending(viewer.connection);
            TsUnits.Gate gate = session.viewerGates.computeIfAbsent(viewer.getUUID(), id -> new TsUnits.Gate());
            return gate.pass(unitStart, key, behind > VIEWER_QUEUE_LIMIT, behind < VIEWER_QUEUE_LIMIT / 2);
        });
    }

    /** How much video may wait on a viewer's connection before frames are left out for them. */
    private static final long VIEWER_QUEUE_LIMIT = 192 * 1024;

    private static void deliver(MinecraftServer server, ServerPlayer caster, Session session, String channel, byte[] out) {
        deliver(server, caster, session, channel, out, viewer -> true);
    }

    /** Sends to the players near the display, and tells those who left its range that the cast is gone for them. */
    private static void deliver(MinecraftServer server, ServerPlayer caster, Session session, String channel, byte[] out,
                                java.util.function.Predicate<ServerPlayer> passes) {
        ServerConfig config = ServerConfig.get();
        Set<UUID> reached = new HashSet<>();
        ServerLevel level = levelOf(server, session.world);
        if (level != null) {
            double range2 = config.viewDistance * config.viewDistance;
            for (ServerPlayer viewer : level.players()) {
                if (viewer == caster || viewer.distanceToSqr(session.x, session.y, session.z) > range2) continue;
                if (!BeamNet.canSend(viewer, channel)) continue;
                // a viewer whose frames are left out for now is still in range
                if (passes.test(viewer)) BeamNet.send(viewer, channel, out);
                reached.add(viewer.getUUID());
            }
        }
        for (Iterator<UUID> it = session.viewers.iterator(); it.hasNext(); ) {
            UUID viewer = it.next();
            if (reached.contains(viewer)) continue;
            it.remove();
            session.viewerGates.remove(viewer);
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
        byte[] out = new BeamPayloads.Audio(session.display, session.caster, payload.seq(), payload.data()).encode();
        for (UUID id : session.viewers) {
            ServerPlayer viewer = server.getPlayerList().getPlayer(id);
            if (viewer != null) send(viewer, BeamPayloads.AUDIO, out);
        }
    }

    private static ServerLevel levelOf(MinecraftServer server, String world) {
        for (ServerLevel level : server.getAllLevels()) {
            //#if MC >= 1.21.11
            var id = level.dimension().identifier();
            //#else
            var id = level.dimension().location();
            //#endif
            if (id.toString().equals(world)) return level;
        }
        return null;
    }

    private static void sendStop(MinecraftServer server, UUID viewer, UUID display) {
        ServerPlayer player = server.getPlayerList().getPlayer(viewer);
        if (player != null) send(player, BeamPayloads.STOP, new BeamPayloads.Stop(display).encode());
    }

    private static void end(MinecraftServer server, Session session) {
        if (SESSIONS.get(session.display) != session) return;
        SESSIONS.remove(session.display);
        for (UUID viewer : session.viewers) sendStop(server, viewer, session.display);
        DdBeam.LOGGER.info("Cast onto display {} ended", session.display);
    }
}
