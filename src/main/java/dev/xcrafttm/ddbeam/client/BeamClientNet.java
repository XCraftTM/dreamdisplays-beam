package dev.xcrafttm.ddbeam.client;

import dev.xcrafttm.ddbeam.net.BeamNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
//#if MC < 1.20.5
import dev.xcrafttm.ddbeam.Ids;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
//#endif

import java.util.function.Consumer;

/** The client side of {@link BeamNet}: Beam's channels on each Minecraft version. */
public final class BeamClientNet {
    private BeamClientNet() {
    }

    /** A packet from the server, handled on the client thread. */
    public static void receive(String channel, Consumer<byte[]> handler) {
        //#if MC >= 1.20.5
        ClientPlayNetworking.registerGlobalReceiver(BeamNet.type(channel), (payload, ctx) -> handler.accept(payload.data()));
        //#else
        ClientPlayNetworking.registerGlobalReceiver(Ids.beam(channel), (client, listener, buf, sender) -> {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            client.execute(() -> handler.accept(data));
        });
        //#endif
    }

    /** Whether the server takes this channel (it has Beam, as a mod or a plugin). */
    public static boolean canSend(String channel) {
        try {
            //#if MC >= 1.20.5
            return ClientPlayNetworking.canSend(BeamNet.type(channel));
            //#else
            return ClientPlayNetworking.canSend(Ids.beam(channel));
            //#endif
        } catch (IllegalStateException e) {
            // not connected
            return false;
        }
    }

    /** Client thread. */
    public static void send(String channel, byte[] data) {
        //#if MC >= 1.20.5
        ClientPlayNetworking.send(new BeamNet.Raw(BeamNet.type(channel), data));
        //#else
        ClientPlayNetworking.send(Ids.beam(channel), new FriendlyByteBuf(Unpooled.wrappedBuffer(data)));
        //#endif
    }
}
