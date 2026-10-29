package dev.xcrafttm.ddbeam.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The server's stream port (streamPort in the config, off by default): Beam players connect to it next to the game
 * connection, with a token they got over that one, and casts' video goes over it both ways (see StreamLink).
 */
final class StreamPort {
    /** Connections that have not introduced themselves yet, at most. */
    private static final int MAX_WAITING = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BeamPaper plugin;
    private final int port;
    private final String host;
    private volatile ServerSocket listener;
    private final Map<UUID, byte[]> tokens = new ConcurrentHashMap<>();
    private final Map<UUID, StreamLink> links = new ConcurrentHashMap<>();
    private final Set<UUID> offered = ConcurrentHashMap.newKeySet();
    private final AtomicInteger waiting = new AtomicInteger();

    StreamPort(BeamPaper plugin, int port, String host) {
        this.plugin = plugin;
        this.port = port;
        this.host = host == null ? "" : host;
    }

    void start() {
        if (port <= 0) return;
        try {
            ServerSocket socket = new ServerSocket(port);
            listener = socket;
            Thread accepting = new Thread(() -> acceptLoop(socket), "ddbeam-stream-port");
            accepting.setDaemon(true);
            accepting.start();
            plugin.getLogger().info("Stream port open on " + port + ": casts' video goes over it, next to the game connection");
        } catch (IOException e) {
            plugin.getLogger().warning("Could not open the stream port " + port + ", casts go over the game connection: " + e);
        }
    }

    void stop() {
        ServerSocket socket = listener;
        listener = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        for (StreamLink link : links.values()) link.close();
        links.clear();
        tokens.clear();
        offered.clear();
    }

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                if (waiting.incrementAndGet() > MAX_WAITING) {
                    waiting.decrementAndGet();
                    client.close();
                    continue;
                }
                Thread hello = new Thread(() -> welcome(client), "ddbeam-stream-hello");
                hello.setDaemon(true);
                hello.start();
            } catch (IOException e) {
                if (!socket.isClosed()) plugin.getLogger().warning("Stream port: " + e);
            }
        }
    }

    private void welcome(Socket client) {
        try {
            StreamLink.Hello hello = StreamLink.handshake(client);
            byte[] token = tokens.get(hello.player());
            if (token == null || !Arrays.equals(token, hello.token())) {
                StreamLink.refuse(client);
                return;
            }
            UUID player = hello.player();
            StreamLink[] self = new StreamLink[1];
            StreamLink link = StreamLink.accept(client, hello,
                    (type, data) -> Bukkit.getScheduler().runTask(plugin, () -> plugin.fromLink(player, type, data)),
                    () -> {
                        if (links.remove(player, self[0]) && plugin.isEnabled()) {
                            Bukkit.getScheduler().runTask(plugin, () -> plugin.linkLost(player));
                        }
                    });
            self[0] = link;
            StreamLink old = links.put(player, link);
            if (old != null) old.close();
        } catch (IOException e) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        } finally {
            waiting.decrementAndGet();
        }
    }

    /** The player's stream connection, null when it has none. */
    StreamLink link(UUID player) {
        StreamLink link = links.get(player);
        return link != null && link.isOpen() ? link : null;
    }

    /** Main thread: offers the stream port to a Beam player that was not told about it yet. */
    void offer(Player player, String channel) {
        if (listener == null || offered.contains(player.getUniqueId()) || !player.getListeningPluginChannels().contains(channel)) return;
        byte[] token = tokens.computeIfAbsent(player.getUniqueId(), id -> {
            byte[] t = new byte[StreamLink.TOKEN];
            RANDOM.nextBytes(t);
            return t;
        });
        // the mod's BeamPayloads.Port: VarInt port, length-prefixed token, length-prefixed host
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varInt(out, port);
        varInt(out, token.length);
        out.writeBytes(token);
        byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
        varInt(out, hostBytes.length);
        out.writeBytes(hostBytes);
        player.sendPluginMessage(plugin, channel, out.toByteArray());
        offered.add(player.getUniqueId());
    }

    void forget(UUID player) {
        tokens.remove(player);
        offered.remove(player);
        StreamLink link = links.remove(player);
        if (link != null) link.close();
    }

    private static void varInt(ByteArrayOutputStream out, int v) {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }
}
