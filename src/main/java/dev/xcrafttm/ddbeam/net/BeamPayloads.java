package dev.xcrafttm.ddbeam.net;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * Everything Beam sends between a client and the server. The server only relays: a caster announces a cast
 * ({@link Start}), sends its video ({@link Stream}, or {@link Frame}s without an H.264 encoder) and audio, and ends
 * it ({@link Stop}); the server passes it on to the players near the display and tells everyone when a cast ends.
 * <p>
 * Every packet stays below Minecraft's own size limits (about 32 KB to the server), so no packet splitting of the mod
 * loader is needed and a plain Paper plugin can relay them too. The wire format (also in the Paper plugin): UUID =
 * two big-endian longs, int = VarInt, bytes = VarInt length + bytes, string = VarInt length + UTF-8. Data from the
 * server carries the caster right after the display, in place of the zero UUID the caster sends.
 */
public final class BeamPayloads {
    /** Largest piece of data in one packet. */
    public static final int MAX_PART = 30_000;
    /** Largest JPEG frame (sent in parts). */
    public static final int MAX_FRAME_BYTES = 1 << 20;

    private BeamPayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("ddbeam", path));
    }

    /** Caster to server: "I am casting onto this display now", with the picture size. */
    public record Start(UUID display, int width, int height) implements CustomPacketPayload {
        public static final Type<Start> TYPE = typeOf("start");
        public static final StreamCodec<ByteBuf, Start> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Start::display, ByteBufCodecs.VAR_INT, Start::width, ByteBufCodecs.VAR_INT, Start::height,
                Start::new);

        @Override
        public Type<Start> type() {
            return TYPE;
        }
    }

    /** Both ways: a cast ends (caster to server, server to the players that may have seen it). */
    public record Stop(UUID display) implements CustomPacketPayload {
        public static final Type<Stop> TYPE = typeOf("stop");
        public static final StreamCodec<ByteBuf, Stop> CODEC = StreamCodec.composite(UUIDUtil.STREAM_CODEC, Stop::display, Stop::new);

        @Override
        public Type<Stop> type() {
            return TYPE;
        }
    }

    /**
     * Both ways: part {@code part} of {@code parts} of one JPEG frame ({@code seq}); only used when the caster has no
     * H.264 encoder. {@code caster} is the casting player (filled in by the server).
     */
    public record Frame(UUID display, UUID caster, int seq, int part, int parts, byte[] data) implements CustomPacketPayload {
        public static final Type<Frame> TYPE = typeOf("frame");
        public static final StreamCodec<ByteBuf, Frame> CODEC = StreamCodec.of(
                (buf, f) -> {
                    UUIDUtil.STREAM_CODEC.encode(buf, f.display());
                    UUIDUtil.STREAM_CODEC.encode(buf, f.caster());
                    ByteBufCodecs.VAR_INT.encode(buf, f.seq());
                    ByteBufCodecs.VAR_INT.encode(buf, f.part());
                    ByteBufCodecs.VAR_INT.encode(buf, f.parts());
                    ByteBufCodecs.byteArray(MAX_PART).encode(buf, f.data());
                },
                buf -> new Frame(UUIDUtil.STREAM_CODEC.decode(buf), UUIDUtil.STREAM_CODEC.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.byteArray(MAX_PART).decode(buf)));

        @Override
        public Type<Frame> type() {
            return TYPE;
        }
    }

    /**
     * Both ways: a piece of a cast's H.264 video (MPEG-TS) at the cast's own size and frame rate. Pieces come in
     * order; a player can start anywhere, the decoder waits for the next key frame.
     */
    public record Stream(UUID display, UUID caster, int seq, int width, int height, byte[] data) implements CustomPacketPayload {
        public static final Type<Stream> TYPE = typeOf("stream");
        public static final StreamCodec<ByteBuf, Stream> CODEC = StreamCodec.of(
                (buf, s) -> {
                    UUIDUtil.STREAM_CODEC.encode(buf, s.display());
                    UUIDUtil.STREAM_CODEC.encode(buf, s.caster());
                    ByteBufCodecs.VAR_INT.encode(buf, s.seq());
                    ByteBufCodecs.VAR_INT.encode(buf, s.width());
                    ByteBufCodecs.VAR_INT.encode(buf, s.height());
                    ByteBufCodecs.byteArray(MAX_PART).encode(buf, s.data());
                },
                buf -> new Stream(UUIDUtil.STREAM_CODEC.decode(buf), UUIDUtil.STREAM_CODEC.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.byteArray(MAX_PART).decode(buf)));

        @Override
        public Type<Stream> type() {
            return TYPE;
        }
    }

    /** Both ways: one packet of a cast's audio, IMA ADPCM, 48 kHz mono (about 40 ms). */
    public record Audio(UUID display, UUID caster, int seq, byte[] data) implements CustomPacketPayload {
        public static final int MAX_BYTES = 16 * 1024;
        public static final Type<Audio> TYPE = typeOf("audio");
        public static final StreamCodec<ByteBuf, Audio> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Audio::display, UUIDUtil.STREAM_CODEC, Audio::caster, ByteBufCodecs.VAR_INT, Audio::seq,
                ByteBufCodecs.byteArray(MAX_BYTES), Audio::data, Audio::new);

        @Override
        public Type<Audio> type() {
            return TYPE;
        }
    }

    /** Server to caster: whether the cast is shared, or why not (a translation key under {@code ddbeam.notice.}). */
    public record Notice(UUID display, String key) implements CustomPacketPayload {
        public static final Type<Notice> TYPE = typeOf("notice");
        public static final StreamCodec<ByteBuf, Notice> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Notice::display, ByteBufCodecs.STRING_UTF8, Notice::key, Notice::new);

        @Override
        public Type<Notice> type() {
            return TYPE;
        }
    }

    public static void register() {
        PayloadTypeRegistry<RegistryFriendlyByteBuf> toServer = PayloadTypeRegistry.serverboundPlay();
        PayloadTypeRegistry<RegistryFriendlyByteBuf> toClient = PayloadTypeRegistry.clientboundPlay();
        toServer.register(Start.TYPE, Start.CODEC);
        toServer.register(Stop.TYPE, Stop.CODEC);
        toServer.register(Frame.TYPE, Frame.CODEC);
        toServer.register(Stream.TYPE, Stream.CODEC);
        toServer.register(Audio.TYPE, Audio.CODEC);
        toClient.register(Stop.TYPE, Stop.CODEC);
        toClient.register(Notice.TYPE, Notice.CODEC);
        toClient.register(Frame.TYPE, Frame.CODEC);
        toClient.register(Stream.TYPE, Stream.CODEC);
        toClient.register(Audio.TYPE, Audio.CODEC);
    }
}
