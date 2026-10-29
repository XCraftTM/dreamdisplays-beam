package dev.xcrafttm.ddbeam.paper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Beam's packet format, read and written by hand (the mod writes it with Minecraft's codecs): UUID = two big-endian
 * longs, int = VarInt, bytes / string = VarInt length + data. Every Beam packet starts with the display's UUID; the
 * data packets (frame, stream, audio) have the caster's UUID right after it.
 */
final class Wire {
    /** Where the caster's UUID sits in frame, stream and audio packets. */
    static final int CASTER_OFFSET = 16;

    private final byte[] data;
    private int at;

    Wire(byte[] data) {
        this.data = data;
    }

    int position() {
        return at;
    }

    UUID uuid() {
        return new UUID(readLong(), readLong());
    }

    private long readLong() {
        if (at + 8 > data.length) throw new IllegalArgumentException("packet too short");
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (data[at++] & 0xFF);
        return v;
    }

    int varInt() {
        int value = 0, shift = 0;
        while (true) {
            if (at >= data.length) throw new IllegalArgumentException("packet too short");
            byte b = data[at++];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
            if (shift >= 35) throw new IllegalArgumentException("VarInt too long");
        }
    }

    /** A length-prefixed byte array. */
    byte[] bytes() {
        int length = varInt();
        if (length < 0 || at + length > data.length) throw new IllegalArgumentException("bad length");
        byte[] out = java.util.Arrays.copyOfRange(data, at, at + length);
        at += length;
        return out;
    }

    /** Skips a length-prefixed byte array and returns its length. */
    int skipBytes() {
        int length = varInt();
        if (length < 0 || at + length > data.length) throw new IllegalArgumentException("bad length");
        at += length;
        return length;
    }

    /** A copy of a data packet with the caster's UUID written in. */
    static byte[] withCaster(byte[] packet, UUID caster) {
        if (packet.length < CASTER_OFFSET + 16) throw new IllegalArgumentException("packet too short");
        byte[] out = packet.clone();
        writeLong(out, CASTER_OFFSET, caster.getMostSignificantBits());
        writeLong(out, CASTER_OFFSET + 8, caster.getLeastSignificantBits());
        return out;
    }

    private static void writeLong(byte[] out, int at, long v) {
        for (int i = 7; i >= 0; i--) {
            out[at + i] = (byte) v;
            v >>>= 8;
        }
    }

    static byte[] uuid(UUID id) {
        byte[] out = new byte[16];
        writeLong(out, 0, id.getMostSignificantBits());
        writeLong(out, 8, id.getLeastSignificantBits());
        return out;
    }

    /** A notice packet: display UUID + string. */
    static byte[] notice(UUID display, String key) {
        byte[] text = key.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[16 + 5 + text.length];
        System.arraycopy(uuid(display), 0, out, 0, 16);
        int at = 16;
        int v = text.length;
        while ((v & ~0x7F) != 0) {
            out[at++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out[at++] = (byte) v;
        System.arraycopy(text, 0, out, at, text.length);
        return java.util.Arrays.copyOf(out, at + text.length);
    }
}
