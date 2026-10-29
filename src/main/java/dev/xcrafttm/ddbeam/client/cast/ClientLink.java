package dev.xcrafttm.ddbeam.client.cast;

import dev.xcrafttm.ddbeam.DdBeam;
import dev.xcrafttm.ddbeam.net.BeamPayloads;
import dev.xcrafttm.ddbeam.net.StreamLink;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.util.UUID;

/**
 * This client's stream connection to the server, when the server offers one (see StreamLink): connected in the
 * background when the server sends its port; until then, and after it breaks, casts' video goes over the game
 * connection.
 */
public final class ClientLink {
    private static volatile StreamLink current;
    /** Counts the offers, so an old attempt that finishes late does not replace a newer one. */
    private static volatile int generation;

    private ClientLink() {
    }

    /** The stream connection, null when there is none. Any thread. */
    public static StreamLink current() {
        StreamLink link = current;
        return link != null && link.isOpen() ? link : null;
    }

    /** Client thread: the server offers its stream port. */
    public static void onPort(BeamPayloads.Port port) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || port.port() <= 0 || port.port() > 65535) return;
        String host = port.host().isBlank() ? gameHost(mc) : port.host();
        UUID player = mc.player.getUUID();
        int mine = ++generation;
        close();
        Thread connecting = new Thread(() -> {
            try {
                StreamLink link = StreamLink.connect(host, port.port(), player, port.token(), ClientLink::received, () -> lost(mine));
                if (generation != mine) {
                    link.close();
                    return;
                }
                current = link;
                DdBeam.LOGGER.info("Stream connection to {}:{}: casts' video goes over it", host, port.port());
            } catch (IOException e) {
                DdBeam.LOGGER.info("No stream connection to {}:{} ({}): casts' video goes over the game connection",
                        host, port.port(), e.toString());
            }
        }, "ddbeam-link-connect");
        connecting.setDaemon(true);
        connecting.start();
    }

    /** The host this client joined the game at; the computer itself in singleplayer. */
    private static String gameHost(Minecraft mc) {
        var server = mc.getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) return "127.0.0.1";
        String ip = server.ip.trim();
        if (ip.startsWith("[")) {
            int end = ip.indexOf(']');
            return end > 0 ? ip.substring(1, end) : ip;
        }
        // host:port (one colon); a bare IPv6 address has more
        int colon = ip.indexOf(':');
        return colon > 0 && colon == ip.lastIndexOf(':') ? ip.substring(0, colon) : ip;
    }

    /** Link reader thread: a frame from the server, handled like the packet it stands for. */
    private static void received(int type, byte[] data) {
        Minecraft.getInstance().execute(() -> {
            try {
                if (type == StreamLink.STREAM) Casts.onStream(BeamPayloads.Stream.decode(data));
                else if (type == StreamLink.FRAME) Casts.onFrame(BeamPayloads.Frame.decode(data));
            } catch (IllegalArgumentException e) {
                // a broken frame is ignored like a broken packet
            }
        });
    }

    private static void lost(int which) {
        if (generation != which) return;
        current = null;
        Minecraft.getInstance().execute(() -> {
            LocalCast cast = Casts.local();
            if (cast != null) cast.linkLost();
        });
    }

    /** Leaving the server. */
    public static void close() {
        StreamLink link = current;
        current = null;
        if (link != null) link.close();
    }
}
