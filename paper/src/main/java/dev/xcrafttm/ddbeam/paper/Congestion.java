package dev.xcrafttm.ddbeam.paper;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOutboundBuffer;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * How far a player's connection is behind: the bytes the server's Netty has not handed to the socket yet (the video
 * and everything after it, the game's own packets too, wait behind them). The way from the player to the Netty channel
 * (the player, its packet listener, its connection) is found by the fields' types, as their names differ between
 * Paper versions; the channel found must lead to the player's own address. Without it nothing counts as behind.
 */
final class Congestion {
    private final Logger log;
    private Field[] path;
    private boolean broken;

    Congestion(Logger log) {
        this.log = log;
    }

    long pending(Player player) {
        if (broken) return 0;
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            if (path == null) path = find(handle, player.getAddress());
            Object at = handle;
            for (Field f : path) {
                at = f.get(at);
                if (at == null) return 0;
            }
            ChannelOutboundBuffer out = ((Channel) at).unsafe().outboundBuffer();
            return out == null ? 0 : out.totalPendingWriteBytes();
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            log.warning("Cannot see how far behind players' connections are, streams are not thinned out: " + e);
            return 0;
        }
    }

    /** Player, packet listener, connection, channel: three fields of game classes, the last one a Netty channel. */
    private static Field[] find(Object handle, InetSocketAddress address) throws ReflectiveOperationException {
        for (Field a : fields(handle.getClass())) {
            Object listener = a.get(handle);
            if (listener == null) continue;
            for (Field b : fields(listener.getClass())) {
                Object connection = b.get(listener);
                if (connection == null) continue;
                for (Field c : fields(connection.getClass())) {
                    if (!Channel.class.isAssignableFrom(c.getType())) continue;
                    Channel channel = (Channel) c.get(connection);
                    if (channel != null && sameAddress(channel.remoteAddress(), address)) return new Field[]{a, b, c};
                }
            }
        }
        throw new NoSuchFieldException("the player's connection");
    }

    private static boolean sameAddress(SocketAddress remote, InetSocketAddress address) {
        return address == null || remote == null || remote.equals(address)
                || remote instanceof InetSocketAddress r && r.getPort() == address.getPort() && r.getAddress().equals(address.getAddress());
    }

    /** The instance fields of [type] and its superclasses that hold game objects. */
    private static List<Field> fields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType().isArray()) continue;
                String name = f.getType().getName();
                if (!name.startsWith("net.minecraft.") && !Channel.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                } catch (RuntimeException e) {
                    continue;
                }
                out.add(f);
            }
        }
        return out;
    }
}
