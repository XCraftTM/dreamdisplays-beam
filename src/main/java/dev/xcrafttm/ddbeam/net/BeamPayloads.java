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
 * ({@link Start}), sends its frames as JPEG ({@link Frame}) and ends it ({@link Stop}); the server passes the frames
 * on to the players near the display and tells everyone when a cast ends.
 */
public final class BeamPayloads {
    /** Largest JPEG frame that is relayed (Fabric splits payloads above Minecraft's packet limits itself). */
    public static final int MAX_FRAME_BYTES = 1 << 20;

    private BeamPayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("ddbeam", path));
    }

    /** Caster to server: "I am casting onto this display now", with the picture size the frames will have. */
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

    /** Both ways: one JPEG frame of a cast; {@code caster} is the casting player (filled in by the server). */
    public record Frame(UUID display, UUID caster, int seq, byte[] jpeg) implements CustomPacketPayload {
        public static final Type<Frame> TYPE = typeOf("frame");
        public static final StreamCodec<ByteBuf, Frame> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Frame::display, UUIDUtil.STREAM_CODEC, Frame::caster, ByteBufCodecs.VAR_INT, Frame::seq,
                ByteBufCodecs.byteArray(MAX_FRAME_BYTES), Frame::jpeg, Frame::new);

        @Override
        public Type<Frame> type() {
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
        toServer.registerLarge(Frame.TYPE, Frame.CODEC, MAX_FRAME_BYTES + 64);
        toServer.register(Audio.TYPE, Audio.CODEC);
        toClient.register(Audio.TYPE, Audio.CODEC);
        toClient.register(Stop.TYPE, Stop.CODEC);
        toClient.register(Notice.TYPE, Notice.CODEC);
        toClient.registerLarge(Frame.TYPE, Frame.CODEC, MAX_FRAME_BYTES + 64);
    }
}
