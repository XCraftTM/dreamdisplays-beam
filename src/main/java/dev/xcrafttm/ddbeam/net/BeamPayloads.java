package dev.xcrafttm.ddbeam.net;

import java.util.UUID;

/**
 * Everything Beam sends between a client and the server. The server only relays: a caster announces a cast
 * ({@link Start}), sends its video ({@link Stream}, or {@link Frame}s without an H.264 encoder) and audio, and ends
 * it ({@link Stop}); the server passes it on to the players near the display and tells everyone when a cast ends.
 * <p>
 * Each packet goes on its own channel ({@code ddbeam:<name>}) as plain bytes in the {@link Wire} format, so it is the
 * same on every Minecraft version and a Paper plugin can relay it too. Every packet stays below Minecraft's size
 * limits (about 32 KB to the server). Data from the server carries the caster right after the display, in place of
 * the zero UUID the caster sends.
 */
public final class BeamPayloads {
    /** Largest piece of data in one packet. */
    public static final int MAX_PART = 30_000;
    /** Largest JPEG frame (sent in parts). */
    public static final int MAX_FRAME_BYTES = 1 << 20;

    public static final String START = "start", STOP = "stop", FRAME = "frame", STREAM = "stream", AUDIO = "audio", NOTICE = "notice";
    /** A caster's delay probe (8 bytes), which the server sends straight back: see DelayProbe. */
    public static final String PROBE = "probe";
    /** What a player's client can decode ({@link Hello}), and what everyone on the server can ({@link Codecs}). */
    public static final String HELLO = "hello", CODECS = "codecs";
    /** Video codecs, as bits. */
    public static final int H264 = 1, HEVC = 2;
    /** Beam's protocol version in {@link Hello}. */
    public static final int PROTOCOL = 2;
    /** Channels a client sends on, and the ones the server sends on. */
    public static final String[] TO_SERVER = {START, STOP, FRAME, STREAM, AUDIO, PROBE, HELLO};
    public static final String[] TO_CLIENT = {STOP, FRAME, STREAM, AUDIO, NOTICE, PROBE, CODECS};

    private BeamPayloads() {
    }

    /** Caster to server: "I am casting onto this display now", with the picture size. */
    public record Start(UUID display, int width, int height) {
        public byte[] encode() {
            return new Wire.Writer().uuid(display).varInt(width).varInt(height).done();
        }

        public static Start decode(byte[] data) {
            Wire.Reader r = new Wire.Reader(data);
            return new Start(r.uuid(), r.varInt(), r.varInt());
        }
    }

    /** Both ways: a cast ends (caster to server, server to the players that may have seen it). */
    /** To the server, once: the client's protocol version and the codecs it can decode (bits). */
    public record Hello(int protocol, int decoders) {
        public byte[] encode() {
            return new Wire.Writer().varInt(protocol).varInt(decoders).done();
        }

        public static Hello decode(byte[] data) {
            Wire.Reader r = new Wire.Reader(data);
            return new Hello(r.varInt(), r.varInt());
        }
    }

    /**
     * To the clients: the codecs every Beam player on the server can decode (bits); a player whose Beam sends no
     * {@link Hello} counts as H.264 only.
     */
    public record Codecs(int codecs) {
        public byte[] encode() {
            return new Wire.Writer().varInt(codecs).done();
        }

        public static Codecs decode(byte[] data) {
            return new Codecs(new Wire.Reader(data).varInt());
        }
    }

    public record Stop(UUID display) {
        public byte[] encode() {
            return new Wire.Writer().uuid(display).done();
        }

        public static Stop decode(byte[] data) {
            return new Stop(new Wire.Reader(data).uuid());
        }
    }

    /**
     * Both ways: part {@code part} of {@code parts} of one JPEG frame ({@code seq}); only used when the caster has no
     * H.264 encoder. {@code caster} is the casting player (filled in by the server).
     */
    public record Frame(UUID display, UUID caster, int seq, int part, int parts, byte[] data) {
        public byte[] encode() {
            return new Wire.Writer().uuid(display).uuid(caster).varInt(seq).varInt(part).varInt(parts).bytes(data).done();
        }

        public static Frame decode(byte[] bytes) {
            Wire.Reader r = new Wire.Reader(bytes);
            return new Frame(r.uuid(), r.uuid(), r.varInt(), r.varInt(), r.varInt(), r.bytes(MAX_PART));
        }
    }

    /**
     * Both ways: a piece of a cast's H.264 video (MPEG-TS) at the cast's own size and frame rate. Pieces come in
     * order; a player can start anywhere, the decoder waits for the next key frame.
     */
    public record Stream(UUID display, UUID caster, int seq, int width, int height, byte[] data) {
        public byte[] encode() {
            return new Wire.Writer().uuid(display).uuid(caster).varInt(seq).varInt(width).varInt(height).bytes(data).done();
        }

        public static Stream decode(byte[] bytes) {
            Wire.Reader r = new Wire.Reader(bytes);
            return new Stream(r.uuid(), r.uuid(), r.varInt(), r.varInt(), r.varInt(), r.bytes(MAX_PART));
        }
    }

    /** Both ways: one packet of a cast's audio, IMA ADPCM, 48 kHz mono (about 40 ms). */
    public record Audio(UUID display, UUID caster, int seq, byte[] data) {
        public static final int MAX_BYTES = 16 * 1024;

        public byte[] encode() {
            return new Wire.Writer().uuid(display).uuid(caster).varInt(seq).bytes(data).done();
        }

        public static Audio decode(byte[] bytes) {
            Wire.Reader r = new Wire.Reader(bytes);
            return new Audio(r.uuid(), r.uuid(), r.varInt(), r.bytes(MAX_BYTES));
        }
    }

    /** Server to caster: whether the cast is shared, or why not (a translation key under {@code ddbeam.notice.}). */
    public record Notice(UUID display, String key) {
        public byte[] encode() {
            return new Wire.Writer().uuid(display).string(key).done();
        }

        public static Notice decode(byte[] bytes) {
            Wire.Reader r = new Wire.Reader(bytes);
            return new Notice(r.uuid(), r.string(256));
        }
    }
}
