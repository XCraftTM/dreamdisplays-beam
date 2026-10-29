package dev.xcrafttm.ddbeam.server;

import dev.xcrafttm.ddbeam.DdBeam;
import dev.xcrafttm.ddbeam.net.BeamNet;
import dev.xcrafttm.ddbeam.net.BeamPayloads;
import dev.xcrafttm.ddbeam.net.StreamLink;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The server's stream port (ServerConfig.streamPort, off by default): Beam players connect to it next to the game
 * connection, with a token they got over that one, and casts' video goes over it both ways (see StreamLink).
 */
final class StreamPort {
    /** Connections that have not introduced themselves yet, at most. */
    private static final int MAX_WAITING = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile ServerSocket listener;
    private static final Map<UUID, byte[]> TOKENS = new ConcurrentHashMap<>();
    private static final Map<UUID, StreamLink> LINKS = new ConcurrentHashMap<>();
    private static final Set<UUID> OFFERED = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger WAITING = new AtomicInteger();

    private StreamPort() {
    }

    static void start(MinecraftServer server) {
        int port = ServerConfig.get().streamPort;
        if (port <= 0) return;
        try {
            ServerSocket socket = new ServerSocket(port);
            listener = socket;
            Thread accepting = new Thread(() -> acceptLoop(server, socket), "ddbeam-stream-port");
            accepting.setDaemon(true);
            accepting.start();
            DdBeam.LOGGER.info("Stream port open on {}: casts' video goes over it, next to the game connection", port);
        } catch (IOException e) {
            DdBeam.LOGGER.warn("Could not open the stream port {}, casts go over the game connection: {}", port, e.toString());
        }
    }

    static void stop() {
        ServerSocket socket = listener;
        listener = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        for (StreamLink link : LINKS.values()) link.close();
        LINKS.clear();
        TOKENS.clear();
        OFFERED.clear();
    }

    private static void acceptLoop(MinecraftServer server, ServerSocket socket) {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                if (WAITING.incrementAndGet() > MAX_WAITING) {
                    WAITING.decrementAndGet();
                    client.close();
                    continue;
                }
                Thread hello = new Thread(() -> welcome(server, client), "ddbeam-stream-hello");
                hello.setDaemon(true);
                hello.start();
            } catch (IOException e) {
                if (!socket.isClosed()) DdBeam.LOGGER.warn("Stream port: {}", e.toString());
            }
        }
    }

    private static void welcome(MinecraftServer server, Socket client) {
        try {
            StreamLink.Hello hello = StreamLink.handshake(client);
            byte[] token = TOKENS.get(hello.player());
            if (token == null || !Arrays.equals(token, hello.token())) {
                StreamLink.refuse(client);
                return;
            }
            UUID player = hello.player();
            StreamLink[] self = new StreamLink[1];
            StreamLink link = StreamLink.accept(client, hello,
                    (type, data) -> server.execute(() -> CastRelay.fromLink(server, player, type, data)),
                    () -> {
                        if (LINKS.remove(player, self[0])) server.execute(() -> CastRelay.linkLost(player));
                    });
            self[0] = link;
            StreamLink old = LINKS.put(player, link);
            if (old != null) old.close();
        } catch (IOException e) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        } finally {
            WAITING.decrementAndGet();
        }
    }

    /** The player's stream connection, null when it has none. */
    static StreamLink link(UUID player) {
        StreamLink link = LINKS.get(player);
        return link != null && link.isOpen() ? link : null;
    }

    /** Server thread: offers the stream port to a Beam player that was not told about it yet. */
    static void offer(ServerPlayer player) {
        if (listener == null || OFFERED.contains(player.getUUID()) || !BeamNet.canSend(player, BeamPayloads.PORT)) return;
        byte[] token = TOKENS.computeIfAbsent(player.getUUID(), id -> {
            byte[] t = new byte[StreamLink.TOKEN];
            RANDOM.nextBytes(t);
            return t;
        });
        ServerConfig config = ServerConfig.get();
        BeamNet.send(player, BeamPayloads.PORT, new BeamPayloads.Port(config.streamPort, token, config.streamHost).encode());
        OFFERED.add(player.getUUID());
    }

    static void forget(UUID player) {
        TOKENS.remove(player);
        OFFERED.remove(player);
        StreamLink link = LINKS.remove(player);
        if (link != null) link.close();
    }
}
