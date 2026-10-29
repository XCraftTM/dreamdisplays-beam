package dev.xcrafttm.ddbeam.client;

import dev.xcrafttm.ddbeam.Texts;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.xcrafttm.ddbeam.client.capture.CaptureSource;
import dev.xcrafttm.ddbeam.client.capture.Sources;
import dev.xcrafttm.ddbeam.client.cast.CastFeed;
import dev.xcrafttm.ddbeam.client.cast.ClientLink;
import dev.xcrafttm.ddbeam.client.cast.Casts;
import dev.xcrafttm.ddbeam.client.cast.LocalCast;
import dev.xcrafttm.ddbeam.client.cast.Targets;
import dev.xcrafttm.ddbeam.client.ui.BeamScreen;
import dev.xcrafttm.ddbeam.net.BeamPayloads;
import net.fabricmc.api.ClientModInitializer;
//#if MC >= 1.19
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
//#else
import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v1.FabricClientCommandSource;
//#endif
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//#if MC >= 26.1
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//#else
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
//#endif
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

//#if MC >= 26.1
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
//#elif MC >= 1.19
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;
//#else
import static net.fabricmc.fabric.api.client.command.v1.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v1.ClientCommandManager.literal;
//#endif

/** Dream Displays Beam, client side: casting, showing other players' casts, the cast dialog and commands. */
public final class DdBeamClient implements ClientModInitializer {
    private static KeyMapping openKey;
    /** Commands open screens one tick later, after the chat has closed. */
    private static Runnable nextTick;

    @Override
    public void onInitializeClient() {
        BeamConfig.load();
        //#if MC >= 26.1
        KeyMapping.Category category = KeyMapping.Category.register(dev.xcrafttm.ddbeam.Ids.beam("main"));
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.ddbeam.menu", InputConstants.KEY_B, category));
        //#elif MC >= 1.21.11
        KeyMapping.Category category = KeyMapping.Category.register(dev.xcrafttm.ddbeam.Ids.beam("main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.ddbeam.menu", InputConstants.KEY_B, category));
        //#else
        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.ddbeam.menu", InputConstants.KEY_B, "key.categories.ddbeam"));
        //#endif

        receive(BeamPayloads.FRAME, data -> Casts.onFrame(BeamPayloads.Frame.decode(data)));
        receive(BeamPayloads.STREAM, data -> Casts.onStream(BeamPayloads.Stream.decode(data)));
        receive(BeamPayloads.AUDIO, data -> Casts.onAudio(BeamPayloads.Audio.decode(data)));
        receive(BeamPayloads.STOP, data -> Casts.onStop(BeamPayloads.Stop.decode(data).display()));
        receive(BeamPayloads.NOTICE, data -> Casts.onNotice(BeamPayloads.Notice.decode(data)));
        receive(BeamPayloads.PROBE, Casts::onProbe);
        receive(BeamPayloads.CODECS, data -> Casts.onCodecs(BeamPayloads.Codecs.decode(data)));
        receive(BeamPayloads.PORT, data -> ClientLink.onPort(BeamPayloads.Port.decode(data)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(Casts::clear));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Runnable task = nextTick;
            nextTick = null;
            if (task != null) task.run();
            while (openKey.consumeClick()) openDialog(mc);
            Casts.tick(mc);
        });
        //#if MC >= 1.19
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(commands()));
        //#else
        ClientCommandManager.DISPATCHER.register(commands());
        //#endif
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
            Casts.message(Texts.tr("ddbeam.error.no_display"));
            return false;
        }
        Mc.show(new BeamScreen(null, target));
        return true;
    }

    private static int feedback(CommandContext<FabricClientCommandSource> ctx, Component text) {
        ctx.getSource().sendFeedback(Texts.tr("ddbeam.prefix").append(text));
        return 1;
    }

    /** Casts the source onto the display the player looks at. */
    private static int startOn(CommandContext<FabricClientCommandSource> ctx, CaptureSource source) {
        if (source == null) return feedback(ctx, Texts.tr("ddbeam.error.no_source"));
        Targets.Target target = Targets.lookedAt(64);
        if (target == null) return feedback(ctx, Texts.tr("ddbeam.error.no_display"));
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
                    if (Casts.local() == null) return feedback(ctx, Texts.tr("ddbeam.cast.none"));
                    Casts.stop();
                    return 1;
                }))
                .then(literal("sources").executes(ctx -> {
                    int i = 0;
                    for (CaptureSource s : Sources.monitors()) feedback(ctx, Texts.tr("ddbeam.command.source.monitor", ++i, s.label()));
                    List<CaptureSource> cams = Sources.cameras();
                    i = 0;
                    if (cams != null) for (CaptureSource s : cams) feedback(ctx, Texts.tr("ddbeam.command.source.camera", ++i, s.label()));
                    i = 0;
                    for (CaptureSource s : Sources.windows()) feedback(ctx, Texts.tr("ddbeam.command.source.window", ++i, s.label()));
                    for (Sources.AudioDevice d : Sources.audioDevices()) feedback(ctx, Texts.tr("ddbeam.command.source.audio", d.name()));
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
                        .then(literal("test").requires(source -> CaptureSource.testOffered())
                                .executes(ctx -> startOn(ctx, CaptureSource.test()))))
                .then(literal("reload").executes(ctx -> {
                    BeamConfig.load();
                    Sources.refresh();
                    return feedback(ctx, Texts.tr("ddbeam.command.reloaded"));
                }))
                .then(literal("status").executes(ctx -> {
                    LocalCast cast = Casts.local();
                    if (cast == null) {
                        feedback(ctx, Texts.tr("ddbeam.cast.none"));
                    } else {
                        feedback(ctx, Texts.tr("ddbeam.command.status.local", cast.source.label(), cast.width, cast.height,
                                cast.settings.fps(), cast.frames(), (cast.sharedWithOthers()
                                        ? Texts.tr("ddbeam.command.status.shared", cast.streamKind().toUpperCase(java.util.Locale.ROOT))
                                        : Texts.tr("ddbeam.command.status.private")),
                                cast.audioKind().isEmpty() ? "-" : cast.audioKind()));
                    }
                    for (Map.Entry<UUID, CastFeed> e : Casts.feeds().entrySet()) {
                        CastFeed feed = e.getValue();
                        feedback(ctx, Texts.tr("ddbeam.command.status.feed", e.getKey().toString().substring(0, 8),
                                feed.caster == null ? "-" : nameOf(feed.caster), feed.stats(), Casts.audioStats(e.getKey())));
                    }
                    return 1;
                }));
    }

    private static String nameOf(UUID player) {
        var connection = Minecraft.getInstance().getConnection();
        var info = connection == null ? null : connection.getPlayerInfo(player);
        if (info == null) return player.toString().substring(0, 8);
        //#if MC >= 1.21.11
        return info.getProfile().name();
        //#else
        return info.getProfile().getName();
        //#endif
    }
}
