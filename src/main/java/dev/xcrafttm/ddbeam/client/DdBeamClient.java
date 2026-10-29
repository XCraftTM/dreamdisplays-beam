package dev.xcrafttm.ddbeam.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.xcrafttm.ddbeam.client.capture.CaptureSource;
import dev.xcrafttm.ddbeam.client.capture.Sources;
import dev.xcrafttm.ddbeam.client.cast.CastFeed;
import dev.xcrafttm.ddbeam.client.cast.Casts;
import dev.xcrafttm.ddbeam.client.cast.LocalCast;
import dev.xcrafttm.ddbeam.client.cast.Targets;
import dev.xcrafttm.ddbeam.client.ui.BeamScreen;
import dev.xcrafttm.ddbeam.net.BeamPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** Dream Displays Beam, client side: casting, showing other players' casts, the cast dialog and commands. */
public final class DdBeamClient implements ClientModInitializer {
    private static KeyMapping openKey;
    /** Commands open screens one tick later, after the chat has closed. */
    private static Runnable nextTick;

    @Override
    public void onInitializeClient() {
        BeamConfig.load();
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("ddbeam", "main"));
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.ddbeam.menu", InputConstants.KEY_B, category));

        receive(BeamPayloads.FRAME, data -> Casts.onFrame(BeamPayloads.Frame.decode(data)));
        receive(BeamPayloads.STREAM, data -> Casts.onStream(BeamPayloads.Stream.decode(data)));
        receive(BeamPayloads.AUDIO, data -> Casts.onAudio(BeamPayloads.Audio.decode(data)));
        receive(BeamPayloads.STOP, data -> Casts.onStop(BeamPayloads.Stop.decode(data).display()));
        receive(BeamPayloads.NOTICE, data -> Casts.onNotice(BeamPayloads.Notice.decode(data)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(Casts::clear));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Runnable task = nextTick;
            nextTick = null;
            if (task != null) task.run();
            while (openKey.consumeClick()) openDialog(mc);
            Casts.tick(mc);
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(commands()));
        // the cameras take FFmpeg a moment to list: ask early
        Sources.refresh();
    }

    /** A broken packet from the server is ignored. */
    private static void receive(String channel, java.util.function.Consumer<byte[]> handler) {
        BeamClientNet.receive(channel, data -> {
            try {
                handler.accept(data);
            } catch (IllegalArgumentException ignored) {
            }
        });
    }

    private static boolean openDialog(Minecraft mc) {
        if (mc.player == null) return false;
        Targets.Target target = Targets.lookedAt(64);
        if (target == null) {
            Casts.message(Component.translatable("ddbeam.error.no_display"));
            return false;
        }
        mc.setScreenAndShow(new BeamScreen(null, target));
        return true;
    }

    private static int feedback(CommandContext<FabricClientCommandSource> ctx, Component text) {
        ctx.getSource().sendFeedback(Component.translatable("ddbeam.prefix").append(text));
        return 1;
    }

    /** Casts the source onto the display the player looks at. */
    private static int startOn(CommandContext<FabricClientCommandSource> ctx, CaptureSource source) {
        if (source == null) return feedback(ctx, Component.translatable("ddbeam.error.no_source"));
        Targets.Target target = Targets.lookedAt(64);
        if (target == null) return feedback(ctx, Component.translatable("ddbeam.error.no_display"));
        BeamConfig config = BeamConfig.get();
        boolean audio = source.kind() == CaptureSource.Kind.CAMERA ? !"none".equalsIgnoreCase(config.cameraAudio) : config.systemAudio;
        Casts.start(target, new LocalCast.Settings(source, config.maxWidth, config.maxHeight, config.fps,
                config.share && BeamClientNet.canSend(BeamPayloads.START), audio || source.kind() == CaptureSource.Kind.TEST));
        return 1;
    }

    private static <T> T pick(List<T> list, int number) {
        return list != null && number >= 1 && number <= list.size() ? list.get(number - 1) : null;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> commands() {
        return literal("ddbeam")
                .then(literal("menu").executes(ctx -> {
                    nextTick = () -> openDialog(Minecraft.getInstance());
                    return 1;
                }))
                .then(literal("stop").executes(ctx -> {
                    if (Casts.local() == null) return feedback(ctx, Component.translatable("ddbeam.cast.none"));
                    Casts.stop();
                    return 1;
                }))
                .then(literal("sources").executes(ctx -> {
                    int i = 0;
                    for (CaptureSource s : Sources.monitors()) feedback(ctx, Component.translatable("ddbeam.command.source.monitor", ++i, s.label()));
                    List<CaptureSource> cams = Sources.cameras();
                    i = 0;
                    if (cams != null) for (CaptureSource s : cams) feedback(ctx, Component.translatable("ddbeam.command.source.camera", ++i, s.label()));
                    i = 0;
                    for (CaptureSource s : Sources.windows()) feedback(ctx, Component.translatable("ddbeam.command.source.window", ++i, s.label()));
                    for (Sources.AudioDevice d : Sources.audioDevices()) feedback(ctx, Component.translatable("ddbeam.command.source.audio", d.name()));
                    return 1;
                }))
                .then(literal("start")
                        .then(literal("monitor").then(argument("number", IntegerArgumentType.integer(1)).executes(ctx ->
                                startOn(ctx, pick(Sources.monitors(), IntegerArgumentType.getInteger(ctx, "number"))))))
                        .then(literal("camera").then(argument("number", IntegerArgumentType.integer(1)).executes(ctx ->
                                startOn(ctx, pick(Sources.cameras(), IntegerArgumentType.getInteger(ctx, "number"))))))
                        .then(literal("window").then(argument("number", IntegerArgumentType.integer(1)).executes(ctx ->
                                startOn(ctx, pick(Sources.windows(), IntegerArgumentType.getInteger(ctx, "number"))))))
                        .then(literal("title").then(argument("title", StringArgumentType.greedyString()).executes(ctx ->
                                startOn(ctx, CaptureSource.window(StringArgumentType.getString(ctx, "title"))))))
                        .then(literal("test").executes(ctx -> startOn(ctx, CaptureSource.test()))))
                .then(literal("reload").executes(ctx -> {
                    BeamConfig.load();
                    Sources.refresh();
                    return feedback(ctx, Component.translatable("ddbeam.command.reloaded"));
                }))
                .then(literal("status").executes(ctx -> {
                    LocalCast cast = Casts.local();
                    if (cast == null) {
                        feedback(ctx, Component.translatable("ddbeam.cast.none"));
                    } else {
                        feedback(ctx, Component.translatable("ddbeam.command.status.local", cast.source.label(), cast.width, cast.height,
                                cast.settings.fps(), cast.frames(), (cast.sharedWithOthers()
                                        ? Component.translatable("ddbeam.command.status.shared", cast.streamKind().toUpperCase(java.util.Locale.ROOT))
                                        : Component.translatable("ddbeam.command.status.private")),
                                cast.audioKind().isEmpty() ? "-" : cast.audioKind()));
                    }
                    for (Map.Entry<UUID, CastFeed> e : Casts.feeds().entrySet()) {
                        CastFeed feed = e.getValue();
                        feedback(ctx, Component.translatable("ddbeam.command.status.feed", e.getKey().toString().substring(0, 8),
                                feed.caster == null ? "-" : nameOf(feed.caster), feed.stats(), Casts.audioStats(e.getKey())));
                    }
                    return 1;
                }));
    }

    private static String nameOf(UUID player) {
        var connection = Minecraft.getInstance().getConnection();
        var info = connection == null ? null : connection.getPlayerInfo(player);
        return info == null ? player.toString().substring(0, 8) : info.getProfile().name();
    }
}
