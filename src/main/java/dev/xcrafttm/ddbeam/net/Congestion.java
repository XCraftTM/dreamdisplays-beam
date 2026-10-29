package dev.xcrafttm.ddbeam.net;

import dev.xcrafttm.ddbeam.DdBeam;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOutboundBuffer;
import net.minecraft.network.Connection;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How far a game connection is behind: the bytes Netty has not handed to the socket yet. Everything sent on the
 * connection waits behind them, the game's own packets (and the answer to the server's ping) too. The fields are found
 * by their type (their names differ between Minecraft versions); without them nothing counts as behind.
 */
public final class Congestion {
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static volatile boolean broken;

    private Congestion() {
    }

    /** Bytes waiting on the connection of a packet listener (a player's connection, either side). */
    public static long pending(Object listener) {
        if (broken || listener == null) return 0;
        try {
            Object connection = listener instanceof Connection ? listener : field(listener.getClass(), Connection.class).get(listener);
            if (connection == null) return 0;
            Channel channel = (Channel) field(Connection.class, Channel.class).get(connection);
            if (channel == null) return 0;
            ChannelOutboundBuffer out = channel.unsafe().outboundBuffer();
            return out == null ? 0 : out.totalPendingWriteBytes();
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            DdBeam.LOGGER.warn("Cannot see how far behind connections are, streams are not thinned out: {}", e.toString());
            return 0;
        }
    }

    /** The first field of [type] in [owner] or its superclasses. */
    private static Field field(Class<?> owner, Class<?> type) throws NoSuchFieldException {
        String key = owner.getName() + "#" + type.getName();
        Field cached = FIELDS.get(key);
        if (cached != null) return cached;
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == type && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    FIELDS.put(key, f);
                    return f;
                }
            }
        }
        throw new NoSuchFieldException(type.getSimpleName() + " in " + owner.getName());
    }
}
