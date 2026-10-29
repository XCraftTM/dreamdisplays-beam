package dev.xcrafttm.ddbeam.net;

import dev.xcrafttm.ddbeam.Ids;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
//#if MC >= 1.20.5
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//#else
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
//#endif

import java.util.HashMap;
import java.util.Map;

/**
 * Beam's channels ({@code ddbeam:<name>}, plain bytes) on each Minecraft version: from 1.20.5 one custom payload
 * type per channel that carries the bytes as they are, before that Fabric's channel API.
 */
public final class BeamNet {
    private BeamNet() {
    }

    /** Server side: a packet from a player, on the server thread. */
    public interface ServerHandler {
        void handle(ServerPlayer player, byte[] data);
    }

    //#if MC >= 1.20.5
    /** The bytes of one Beam packet, on the channel its type names. */
    public record Raw(CustomPacketPayload.Type<Raw> type, byte[] data) implements CustomPacketPayload {
    }

    private static final Map<String, CustomPacketPayload.Type<Raw>> TYPES = new HashMap<>();

    public static synchronized CustomPacketPayload.Type<Raw> type(String channel) {
        return TYPES.computeIfAbsent(channel, c -> new CustomPacketPayload.Type<>(Ids.beam(c)));
    }

    private static StreamCodec<RegistryFriendlyByteBuf, Raw> codec(CustomPacketPayload.Type<Raw> type) {
        return StreamCodec.of((buf, raw) -> buf.writeBytes(raw.data()), buf -> {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return new Raw(type, data);
        });
    }
    //#endif

    /** Both sides, at start: the channels and their payload types. */
    public static void registerTypes() {
        //#if MC >= 1.20.5
        //#if MC >= 26.1
        var toServer = PayloadTypeRegistry.serverboundPlay();
        var toClient = PayloadTypeRegistry.clientboundPlay();
        //#else
        var toServer = PayloadTypeRegistry.playC2S();
        var toClient = PayloadTypeRegistry.playS2C();
        //#endif
        for (String channel : BeamPayloads.TO_SERVER) toServer.register(type(channel), codec(type(channel)));
        for (String channel : BeamPayloads.TO_CLIENT) toClient.register(type(channel), codec(type(channel)));
        //#endif
    }

    public static void receive(String channel, ServerHandler handler) {
        //#if MC >= 1.20.5
        // Fabric runs play payload handlers on the server thread
        ServerPlayNetworking.registerGlobalReceiver(type(channel), (payload, ctx) -> handler.handle(ctx.player(), payload.data()));
        //#else
        ServerPlayNetworking.registerGlobalReceiver(Ids.beam(channel), (server, player, listener, buf, sender) -> {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            server.execute(() -> handler.handle(player, data));
        });
        //#endif
    }

    public static boolean canSend(ServerPlayer player, String channel) {
        //#if MC >= 1.20.5
        return ServerPlayNetworking.canSend(player, type(channel));
        //#else
        return ServerPlayNetworking.canSend(player, Ids.beam(channel));
        //#endif
    }

    public static void send(ServerPlayer player, String channel, byte[] data) {
        //#if MC >= 1.20.5
        ServerPlayNetworking.send(player, new Raw(type(channel), data));
        //#else
        ServerPlayNetworking.send(player, Ids.beam(channel), new FriendlyByteBuf(Unpooled.wrappedBuffer(data)));
        //#endif
    }
}
