package dev.xcrafttm.ddbeam.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Beam's packet format, the same on every Minecraft version (and in the Paper plugin): UUID = two big-endian longs,
 * int = VarInt, bytes / string = VarInt length + data.
 */
public final class Wire {
    private Wire() {
    }

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(64);

        public Writer uuid(UUID id) {
            writeLong(id.getMostSignificantBits());
            writeLong(id.getLeastSignificantBits());
            return this;
        }

        private void writeLong(long v) {
            for (int i = 56; i >= 0; i -= 8) out.write((int) (v >>> i));
        }

        public Writer varInt(int v) {
            while ((v & ~0x7F) != 0) {
                out.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            out.write(v);
            return this;
        }

        public Writer bytes(byte[] data) {
            varInt(data.length);
            out.write(data, 0, data.length);
            return this;
        }

        public Writer string(String s) {
            return bytes(s.getBytes(StandardCharsets.UTF_8));
        }

        public byte[] done() {
            return out.toByteArray();
        }
    }

    public static final class Reader {
        private final byte[] data;
        private int at;

        public Reader(byte[] data) {
            this.data = data;
        }

        public UUID uuid() {
            return new UUID(readLong(), readLong());
        }

        private long readLong() {
            if (at + 8 > data.length) throw new IllegalArgumentException("packet too short");
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (data[at++] & 0xFF);
            return v;
        }

        public int varInt() {
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

        public byte[] bytes(int max) {
            int length = varInt();
            if (length < 0 || length > max || at + length > data.length) throw new IllegalArgumentException("bad length " + length);
            byte[] out = java.util.Arrays.copyOfRange(data, at, at + length);
            at += length;
            return out;
        }

        public String string(int max) {
            return new String(bytes(max), StandardCharsets.UTF_8);
        }
    }
}
