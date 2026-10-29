package dev.xcrafttm.ddbeam.paper;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dream Displays Beam's relay for Paper servers: the same job as the mod's server part on Fabric. A caster
 * announces a cast, the plugin checks who may cast and passes the cast's video and audio on to the players near the
 * display, within a byte budget. It never decodes anything; the packets go on as they came, with the caster written
 * in. Everything runs on the server thread (Paper hands plugin messages to it).
 */
public final class BeamPaper extends JavaPlugin implements PluginMessageListener, Listener {
    private static final String START = "ddbeam:start", STOP = "ddbeam:stop", FRAME = "ddbeam:frame",
            STREAM = "ddbeam:stream", AUDIO = "ddbeam:audio", NOTICE = "ddbeam:notice",
            PROBE = "ddbeam:probe";
    /** A cast whose caster sent nothing for this long is over. */
    private static final long IDLE_NS = 10_000_000_000L;

    private static final class Session {
        final UUID display, caster;
        final String world;
        final double x, y, z;
        final Set<UUID> viewers = new HashSet<>();
        long lastDataNs = System.nanoTime(), lastFrameNs, lastAudioNs, budgetNs = System.nanoTime();
        double budget;
        int keepSeq = -1;
        /** The cast within the byte budget, and each viewer's connection: whole frames only, see TsUnits. */
        final TsUnits.Gate budgetGate = new TsUnits.Gate();
        final Map<UUID, TsUnits.Gate> viewerGates = new HashMap<>();

        Session(UUID display, UUID caster, Displays.Info info) {
            this.display = display;
            this.caster = caster;
            this.world = info.world();
            this.x = info.x();
            this.y = info.y();
            this.z = info.z();
        }
    }

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Displays displays = new Displays();
    private Congestion congestion;
    /** How much video may wait on a viewer's connection before frames are left out for them. */
    private static final long VIEWER_QUEUE_LIMIT = 192 * 1024;
    private boolean relay;
    private String whoCanCast;
    private double viewDistance;
    private int maxKilobytesPerSecond, maxFps;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        congestion = new Congestion(getLogger());
        loadSettings();
        var messenger = getServer().getMessenger();
        for (String channel : new String[]{START, STOP, FRAME, STREAM, AUDIO, PROBE}) messenger.registerIncomingPluginChannel(this, channel, this);
        for (String channel : new String[]{STOP, FRAME, STREAM, AUDIO, NOTICE, PROBE}) messenger.registerOutgoingPluginChannel(this, channel);
        getServer().getPluginManager().registerEvents(this, this);
        try {
            getServer().getScheduler().runTaskTimer(this, this::endIdle, 20, 20);
        } catch (UnsupportedOperationException e) {
            // Folia has no global scheduler like this; casts then end when their caster leaves or stops
            getLogger().warning("No repeating task on this server; idle casts are ended when their caster leaves");
        }
        if (getServer().getPluginManager().getPlugin("DreamDisplays") == null) {
            getLogger().warning("Dream Displays is not installed: there are no displays to cast onto");
        }
    }

    private void loadSettings() {
        reloadConfig();
        relay = getConfig().getBoolean("relay", true);
        whoCanCast = getConfig().getString("whoCanCast", "owner");
        viewDistance = getConfig().getDouble("viewDistance", 96);
        maxKilobytesPerSecond = getConfig().getInt("maxKilobytesPerSecond", 4096);
        maxFps = getConfig().getInt("maxFps", 30);
    }

    @Override
    public void onDisable() {
        for (Session session : sessions.values().toArray(new Session[0])) end(session);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        try {
            switch (channel) {
                case START -> start(player, message);
                case STOP -> {
                    Session session = sessions.get(new Wire(message).uuid());
                    if (session != null && session.caster.equals(player.getUniqueId())) end(session);
                }
                case FRAME -> frame(player, message);
                case STREAM -> stream(player, message);
                case AUDIO -> audio(player, message);
                // a caster's delay probe goes straight back
                case PROBE -> {
                    if (message.length == 8 && player.getListeningPluginChannels().contains(PROBE)) player.sendPluginMessage(this, PROBE, message);
                }
                default -> {
                }
            }
        } catch (IllegalArgumentException e) {
            // a broken packet: ignored
        }
    }

    private void notice(Player player, UUID display, String key) {
        if (player.getListeningPluginChannels().contains(NOTICE)) player.sendPluginMessage(this, NOTICE, Wire.notice(display, key));
    }

    private void start(Player player, byte[] message) {
        UUID id = new Wire(message).uuid();
        if (!relay) {
            notice(player, id, "relay_off");
            return;
        }
        Displays.Info info = displays.find(id);
        if (info == null) {
            notice(player, id, "unknown_display");
            return;
        }
        if (!mayCast(player, info)) {
            notice(player, id, "not_allowed");
            return;
        }
        Session current = sessions.get(id);
        if (current != null && !current.caster.equals(player.getUniqueId()) && Bukkit.getPlayer(current.caster) != null) {
            notice(player, id, "busy");
            return;
        }
        // one cast per player: a new one ends the one before
        for (Session session : sessions.values().toArray(new Session[0])) {
            if (session.caster.equals(player.getUniqueId())) end(session);
        }
        if (sessions.get(id) != null) end(sessions.get(id));
        sessions.put(id, new Session(id, player.getUniqueId(), info));
        getLogger().info(player.getName() + " casts onto display " + id);
        notice(player, id, "shared");
    }

    private boolean mayCast(Player player, Displays.Info info) {
        boolean admin = player.isOp() || player.hasPermission("ddbeam.cast.any");
        return switch (whoCanCast) {
            case "everyone" -> true;
            case "operators" -> admin;
            default -> admin || player.getUniqueId().equals(info.owner());
        };
    }

    private Session sessionOf(Player player, byte[] message) {
        Session session = sessions.get(new Wire(message).uuid());
        if (session == null || !session.caster.equals(player.getUniqueId())) return null;
        session.lastDataNs = System.nanoTime();
        return session;
    }

    /** Refills the byte budget (at most one second of it) and takes {@code bytes} if they fit. */
    private boolean spend(Session session, double bytes) {
        long now = System.nanoTime();
        double rate = maxKilobytesPerSecond * 1024.0;
        session.budget = Math.min(rate, session.budget + rate * (now - session.budgetNs) / 1e9);
        session.budgetNs = now;
        if (bytes > session.budget) return false;
        session.budget -= bytes;
        return true;
    }

    /** A part of a JPEG frame: whole frames are kept or dropped (frame rate limit, byte budget). */
    private void frame(Player player, byte[] message) {
        Session session = sessionOf(player, message);
        if (session == null) return;
        Wire wire = new Wire(message);
        wire.uuid();
        wire.uuid();
        int seq = wire.varInt(), part = wire.varInt(), parts = wire.varInt();
        if (part == 0) {
            long now = System.nanoTime();
            boolean tooSoon = now - session.lastFrameNs < 1_000_000_000L / Math.max(1, maxFps) * 9 / 10;
            session.keepSeq = !tooSoon && spend(session, (double) message.length * parts) ? seq : -1;
            if (session.keepSeq < 0) return;
            session.lastFrameNs = now;
        } else if (seq != session.keepSeq) {
            return;
        }
        deliver(player, session, FRAME, Wire.withCaster(message, player.getUniqueId()));
    }

    /**
     * A piece of the cast's video. Every frame builds on the ones before, so a frame cannot simply be left out: over
     * the byte budget, or for a viewer whose connection is too far behind, whole frames are left out up to the next key
     * frame (see TsUnits), and the viewers see the last whole picture a little longer instead of a broken one.
     */
    private void stream(Player player, byte[] message) {
        Session session = sessionOf(player, message);
        if (session == null) return;
        Wire wire = new Wire(message);
        wire.uuid();
        wire.uuid();
        wire.varInt();
        wire.varInt();
        wire.varInt();
        byte[] data = wire.bytes();
        boolean unitStart = TsUnits.startsUnit(data), key = unitStart && TsUnits.holdsKey(data);
        boolean fits = spend(session, 0) && session.budget >= message.length;
        if (!session.budgetGate.pass(unitStart, key, !fits, fits)) return;
        session.budget = Math.max(0, session.budget - message.length);
        deliver(player, session, STREAM, Wire.withCaster(message, player.getUniqueId()), viewer -> {
            long behind = congestion.pending(viewer);
            TsUnits.Gate gate = session.viewerGates.computeIfAbsent(viewer.getUniqueId(), id -> new TsUnits.Gate());
            return gate.pass(unitStart, key, behind > VIEWER_QUEUE_LIMIT, behind < VIEWER_QUEUE_LIMIT / 2);
        });
    }

    /** Audio goes to whoever gets the cast's video. */
    private void audio(Player player, byte[] message) {
        Session session = sessionOf(player, message);
        if (session == null) return;
        long now = System.nanoTime();
        if (now - session.lastAudioNs < 10_000_000L) return;
        session.lastAudioNs = now;
        byte[] out = Wire.withCaster(message, player.getUniqueId());
        for (UUID id : session.viewers) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer != null && viewer.getListeningPluginChannels().contains(AUDIO)) viewer.sendPluginMessage(this, AUDIO, out);
        }
    }

    private void deliver(Player caster, Session session, String channel, byte[] out) {
        deliver(caster, session, channel, out, viewer -> true);
    }

    /** Sends to the players near the display, and tells those who left its range that the cast is gone for them. */
    private void deliver(Player caster, Session session, String channel, byte[] out, java.util.function.Predicate<Player> passes) {
        Set<UUID> reached = new HashSet<>();
        World world = Bukkit.getWorld(session.world);
        if (world != null) {
            double range2 = viewDistance * viewDistance;
            Location at = new Location(world, session.x, session.y, session.z);
            for (Player viewer : world.getPlayers()) {
                if (viewer.equals(caster) || viewer.getLocation().distanceSquared(at) > range2) continue;
                if (!viewer.getListeningPluginChannels().contains(channel)) continue;
                // a viewer whose frames are left out for now is still in range
                if (passes.test(viewer)) viewer.sendPluginMessage(this, channel, out);
                reached.add(viewer.getUniqueId());
            }
        }
        for (Iterator<UUID> it = session.viewers.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (reached.contains(id)) continue;
            it.remove();
            session.viewerGates.remove(id);
            sendStop(id, session.display);
        }
        session.viewers.addAll(reached);
    }

    private void sendStop(UUID viewer, UUID display) {
        Player player = Bukkit.getPlayer(viewer);
        if (player != null && player.getListeningPluginChannels().contains(STOP)) player.sendPluginMessage(this, STOP, Wire.uuid(display));
    }

    private void end(Session session) {
        if (sessions.get(session.display) != session) return;
        sessions.remove(session.display);
        for (UUID viewer : session.viewers) sendStop(viewer, session.display);
        getLogger().info("Cast onto display " + session.display + " ended");
    }

    private void endIdle() {
        long now = System.nanoTime();
        for (Session session : sessions.values().toArray(new Session[0])) {
            if (now - session.lastDataNs > IDLE_NS) end(session);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        for (Session session : sessions.values().toArray(new Session[0])) {
            if (session.caster.equals(id)) end(session);
            else session.viewers.remove(id);
        }
    }
}
