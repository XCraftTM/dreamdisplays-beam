package dev.xcrafttm.ddbeam.test;

import com.dreamdisplays.platform.client.displays.DisplayScreen;
import com.dreamdisplays.platform.client.ui.DisplayMenu;
import dev.xcrafttm.ddbeam.client.Mc;
import dev.xcrafttm.ddbeam.client.capture.CaptureSource;
import dev.xcrafttm.ddbeam.client.capture.Sources;
import dev.xcrafttm.ddbeam.client.cast.CastFeed;
import dev.xcrafttm.ddbeam.client.cast.Casts;
import dev.xcrafttm.ddbeam.client.cast.LocalCast;
import dev.xcrafttm.ddbeam.client.cast.Targets;
import dev.xcrafttm.ddbeam.client.ui.BeamScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drives a test run and takes screenshots ({@code run/<run>/screenshots}). {@code -Dddbeam.test.mode}:
 * <ul>
 *   <li>{@code cast} (default): casts {@code -Dddbeam.test.source} (test, monitor1, camera1, window1) onto the
 *       display, shoots it, then Dream Displays' menu with the Cast button and the cast dialog (both tabs).</li>
 *   <li>{@code stream}: opens the world to LAN (port 25700), casts and waits for the viewer.</li>
 *   <li>{@code video}: plays a video on the display, casts (the video must pause), then sets another video (the
 *       cast must end).</li>
 *   <li>{@code viewer}: joins it (see the viewer run in build.gradle), shoots the other player's cast.</li>
 * </ul>
 */
public final class TestDriver implements ClientModInitializer {
    private static final String MODE = System.getProperty("ddbeam.test.mode", "cast");
    private static final String SOURCE = System.getProperty("ddbeam.test.source", "test");
    private static final String PREFIX = System.getProperty("ddbeam.test.prefix", "beam");
    private static final long TIMEOUT_MS = 240_000;
    private static final int RES = Integer.getInteger("ddbeam.test.res", 720);
    private static final int FPS = Integer.getInteger("ddbeam.test.fps", 30);

    private long started, sceneAt, castAt, stepAt, doneAt;
    private int step;
    private boolean viewerSeen;

    @Override
    public void onInitializeClient() {
        if (System.getProperty("ddbeam.test.mode") == null) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        //#if MC < 1.20
        // no --quickPlaySingleplayer before 1.20: open the test world from the title screen
        if (MODE.equals("viewer") || MODE.equals("stream") && System.getProperty("ddbeam.test.server") != null) return;
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!(screen instanceof net.minecraft.client.gui.screens.TitleScreen) || worldOpened) return;
            worldOpened = true;
            //#if MC >= 1.19
            mc.tell(() -> mc.createWorldOpenFlows().loadLevel(screen, "beam-test"));
            //#else
            mc.tell(() -> mc.loadLevel("beam-test"));
            //#endif
        });
        //#endif
    }

    private boolean worldOpened;

    private void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (started == 0) started = now;
        if (doneAt != 0) {
            if (now - doneAt > 1500) mc.stop();
            return;
        }
        if (now - started > TIMEOUT_MS) {
            TestScene.LOG.error("Test timed out in step {}", step);
            status();
            doneAt = now;
            return;
        }
        if (mc.player == null || mc.level == null) return;
        if (MODE.equals("viewer")) {
            viewerTick(mc, now);
            return;
        }
        List<Targets.Target> targets = Targets.all();
        if (targets.isEmpty()) return;
        if (sceneAt == 0) {
            sceneAt = now;
            return;
        }
        // the scene places the player two seconds after joining
        if (now - sceneAt < 4000) return;
        Targets.Target target = targets.get(0);
        if (MODE.equals("click")) {
            clickTick(mc, now, target);
            return;
        }
        if (MODE.equals("video")) {
            videoTick(mc, now, target);
            return;
        }
        if (castAt == 0 && sceneAt != 0 && now - sceneAt < 9000) {
            // game frame rate before the cast, for comparison
            if (now - sceneAt > 8000 && stepAt == 0) {
                stepAt = now;
                TestScene.LOG.info("Game FPS without cast: {}", fps(mc));
            }
            return;
        }
        if (castAt == 0) {
            stepAt = 0;
            if (MODE.equals("stream")) {
                var server = mc.getSingleplayerServer();
                //#if MC >= 26.3
                boolean lan = server != null && server.publishServer(MinecraftServer.MultiplayerScope.LAN, true, 25700);
                //#elif MC >= 26.2
                boolean lan = server != null && server.publishServer(MinecraftServer.MultiplayerScope.LAN,
                        net.minecraft.world.level.GameType.CREATIVE, true, 25700);
                //#else
                boolean lan = server != null && server.publishServer(net.minecraft.world.level.GameType.CREATIVE, true, 25700);
                //#endif
                TestScene.LOG.info("Open to LAN on 25700: {}", lan);
            }
            CaptureSource source = source(mc);
            if (source == null) {
                TestScene.LOG.error("No source {}", SOURCE);
                doneAt = now;
                return;
            }
            TestScene.LOG.info("Casting {} onto {}", source.label().getString(), target.id());
            Casts.start(target, new LocalCast.Settings(source, RES * 16 / 9, RES, FPS, true, true));
            castAt = now;
            return;
        }
        CastFeed feed = Casts.feed(target.id());
        if (MODE.equals("stream")) {
            streamTick(mc, now);
            return;
        }
        switch (step) {
            case 0 -> {
                // let it run a while at full speed first
                if (feed == null || feed.shown() < 30 || now - castAt < 10_000) return;
                status();
                shot(mc, "cast");
                step++;
                stepAt = now;
            }
            case 1 -> {
                // look at it from the side too (Dream Displays' renderer draws it in perspective)
                if (now - stepAt < 500) return;
                //#if MC >= 1.21.11
                mc.player.snapTo(5.5, -60, 6.5, 145, 5);
                //#else
                mc.player.moveTo(5.5, -60, 6.5, 145, 5);
                //#endif
                step++;
                stepAt = now;
            }
            case 2 -> {
                if (now - stepAt < 1500) return;
                shot(mc, "cast-side");
                //#if MC >= 1.21.11
                mc.player.snapTo(0.5, -60, 11.5, 180, 2);
                //#else
                mc.player.moveTo(0.5, -60, 11.5, 180, 2);
                //#endif
                DisplayScreen screen = screenOf(target.id());
                if (screen == null) {
                    TestScene.LOG.error("No DisplayScreen for {}", target.id());
                    doneAt = now;
                    return;
                }
                DisplayMenu.Companion.open(screen);
                step++;
                stepAt = now;
            }
            case 3 -> {
                if (now - stepAt < 2500) return;
                shot(mc, "menu");
                Screen menu = Mc.screen();
                Mc.show(new BeamScreen(menu, target));
                step++;
                stepAt = now;
            }
            case 4 -> {
                if (now - stepAt < 5000) return;
                shot(mc, "dialog");
                if (Mc.screen() instanceof BeamScreen dialog) hoverShare(mc, dialog);
                step = 41;
                stepAt = now;
            }
            case 41 -> {
                if (now - stepAt < 800) return;
                shot(mc, "dialog-share-tip");
                if (Mc.screen() instanceof BeamScreen dialog) dialog.openSelect(0);
                step = 40;
                stepAt = now;
            }
            case 40 -> {
                if (now - stepAt < 800) return;
                shot(mc, "dialog-select");
                if (Mc.screen() instanceof BeamScreen dialog) {
                    // casting a camera: its mode list too
                    dialog.openSelect(SOURCE.startsWith("camera") ? 2 : -1);
                    if (!SOURCE.startsWith("camera")) dialog.showTab(2);
                }
                step = SOURCE.startsWith("camera") ? 42 : 5;
                stepAt = now;
            }
            case 42 -> {
                if (now - stepAt < 800) return;
                shot(mc, "dialog-modes");
                if (Mc.screen() instanceof BeamScreen dialog) dialog.openSelect(-1);
                step = 5;
                stepAt = now;
            }
            case 5 -> {
                if (now - stepAt < 9000) return;
                shot(mc, "dialog-cameras");
                Mc.show(null);
                status();
                Casts.stop();
                doneAt = now;
            }
            default -> {
            }
        }
    }

    /** The caster of a streaming test: keeps casting until the viewer has come and gone. */
    private void streamTick(Minecraft mc, long now) {
        int players = mc.getConnection() == null ? 0 : mc.getConnection().getOnlinePlayers().size();
        if (players > 1 && !viewerSeen) {
            viewerSeen = true;
            TestScene.LOG.info("Viewer joined");
        }
        if (now - stepAt > 5000) {
            stepAt = now;
            status();
        }
        if (viewerSeen && players <= 1) {
            shot(mc, "stream-caster");
            status();
            doneAt = now;
        }
    }

    private static final String VIDEO = System.getProperty("ddbeam.test.video", "https://youtu.be/-ie3I_bHOeU");

    /** A cast and the display's video take turns: the cast pauses the video, a new video ends the cast. */
    private void videoTick(Minecraft mc, long now, Targets.Target target) {
        com.dreamdisplays.api.Display display = null;
        for (com.dreamdisplays.api.Display d : com.dreamdisplays.api.DreamDisplays.getDisplays()) {
            if (d.getId().getUuid().equals(target.id())) display = d;
        }
        if (display == null) return;
        String state = display.getState().getClass().getSimpleName();
        switch (step) {
            case 0 -> {
                TestScene.LOG.info("Video test: setting {}", VIDEO);
                display.setUrl(VIDEO);
                step = 1;
                stepAt = now;
            }
            case 1 -> {
                boolean playing = state.equals("Playing");
                if (!playing && now - stepAt < 60_000) return;
                if (now - stepAt < 4000) return;
                TestScene.LOG.info("Video test: before the cast the video is {}", state);
                shot(mc, "video-playing");
                Casts.start(target, new LocalCast.Settings(CaptureSource.test(), RES * 16 / 9, RES, FPS, true, true));
                castAt = now;
                step = 2;
                stepAt = now;
            }
            case 2 -> {
                if (now - stepAt < 5000) return;
                TestScene.LOG.info("Video test: during the cast the video is {}, cast {}", state, Casts.local() == null ? "none" : "running");
                shot(mc, "video-cast");
                display.setUrl(VIDEO + (VIDEO.contains("?") ? "&" : "?") + "t=60");
                step = 3;
                stepAt = now;
            }
            case 3 -> {
                if (now - stepAt < 6000) return;
                TestScene.LOG.info("Video test: after a new video the cast is {}, the video {}", Casts.local() == null ? "ended" : "STILL RUNNING", state);
                shot(mc, "video-after");
                Casts.stop();
                doneAt = now;
            }
            default -> {
            }
        }
    }

    /** Opens the dialog and waits for real mouse clicks (driven from outside), logging what is chosen. */
    private void clickTick(Minecraft mc, long now, Targets.Target target) {
        if (castAt == 0) {
            Mc.show(new BeamScreen(null, target));
            castAt = now;
            stepAt = now;
            TestScene.LOG.info("Dialog open, waiting for clicks");
            return;
        }
        if (now - stepAt < 1000) return;
        stepAt = now;
        Object selected = null;
        if (Mc.screen() instanceof BeamScreen dialog) {
            try {
                var field = BeamScreen.class.getDeclaredField("selected");
                field.setAccessible(true);
                selected = field.get(dialog);
            } catch (ReflectiveOperationException e) {
                selected = e.toString();
            }
        }
        TestScene.LOG.info("Click test: screen {}, selected {}, casting {}",
                Mc.screen() == null ? "none" : Mc.screen().getClass().getSimpleName(), selected,
                Casts.local() == null ? "no" : Casts.local().source.label().getString());
        if (now - castAt > 90_000 || (Casts.local() != null && now - castAt > 3000 && Mc.screen() == null)) {
            shot(mc, "click");
            Casts.stop();
            doneAt = now;
        }
    }

    private void viewerTick(Minecraft mc, long now) {
        for (Map.Entry<UUID, CastFeed> e : Casts.feeds().entrySet()) {
            CastFeed feed = e.getValue();
            if (feed.caster == null || feed.shown() < 30) continue;
            if (stepAt == 0) {
                stepAt = now;
                return;
            }
            // a few seconds of audio first
            if (now - stepAt < 10000) return;
            status();
            shot(mc, "viewer");
            doneAt = now;
            return;
        }
    }

    private static CaptureSource source(Minecraft mc) {
        if (SOURCE.equals("test")) return CaptureSource.test();
        if (SOURCE.startsWith("title:")) return CaptureSource.window(SOURCE.substring(6));
        int n = Integer.parseInt(SOURCE.replaceAll("\\D", "")) - 1;
        List<CaptureSource> list = SOURCE.startsWith("monitor") ? Sources.monitors()
                : SOURCE.startsWith("window") ? Sources.windows() : Sources.refresh().join();
        return n >= 0 && n < list.size() ? list.get(n) : null;
    }

    private static void status() {
        TestScene.LOG.info("Game FPS: {}", fps(Minecraft.getInstance()));
        LocalCast cast = Casts.local();
        if (cast != null) {
            TestScene.LOG.info("Local cast: {} {}x{} @{}, {} frames, shared {} ({}, {} KB sent), audio '{}'", cast.source.label().getString(),
                    cast.width, cast.height, cast.settings.fps(), cast.frames(), cast.sharedWithOthers(), cast.streamKind(),
                    cast.streamBytes() / 1024, cast.audioKind());
        }
        for (Map.Entry<UUID, CastFeed> e : Casts.feeds().entrySet()) {
            TestScene.LOG.info("Feed {} (caster {}): {}, audio {}", e.getKey(), e.getValue().caster, e.getValue().stats(),
                    Casts.audioStats(e.getKey()));
        }
    }

    private static DisplayScreen screenOf(UUID id) {
        try {
            Class<?> registry = Class.forName("com.dreamdisplays.platform.client.displays.DisplayRegistry");
            Object instance = registry.getField("INSTANCE").get(null);
            for (var m : registry.getMethods()) {
                if (m.getName().equals("getScreens") && Map.class.isAssignableFrom(m.getReturnType())) {
                    return (DisplayScreen) ((Map<?, ?>) m.invoke(instance)).get(id);
                }
            }
        } catch (ReflectiveOperationException e) {
            TestScene.LOG.error("DisplayRegistry", e);
        }
        return null;
    }

    /**
     * Puts the game's mouse position (not the real pointer) on the dialog's "Stream to players" check box, so the
     * next frame draws its tooltip: greyed out with the reason when the server has no Beam relay.
     */
    private static void hoverShare(Minecraft mc, BeamScreen dialog) {
        try {
            java.lang.reflect.Field hitsField = BeamScreen.class.getDeclaredField("hits");
            java.lang.reflect.Field kField = BeamScreen.class.getDeclaredField("k");
            hitsField.setAccessible(true);
            kField.setAccessible(true);
            float k = kField.getFloat(dialog);
            String on = dev.xcrafttm.ddbeam.Texts.tr("ddbeam.dialog.share.tip").getString();
            String off = dev.xcrafttm.ddbeam.Texts.tr("ddbeam.dialog.share.off.title").getString();
            for (Object hit : (List<?>) hitsField.get(dialog)) {
                List<?> tip = (List<?>) call(hit, "tooltip");
                if (tip == null || tip.isEmpty()) continue;
                String first = ((net.minecraft.network.chat.Component) tip.get(0)).getString();
                if (!first.equals(on) && !first.equals(off)) continue;
                Object rect = call(hit, "rect");
                double vx = (int) call(rect, "x") + 6, vy = (int) call(rect, "y") + (int) call(rect, "h") / 2.0;
                var window = mc.getWindow();
                double px = window.getScreenWidth() / (double) window.getGuiScaledWidth();
                double py = window.getScreenHeight() / (double) window.getGuiScaledHeight();
                setMouse(mc, vx / k * px, vy / k * py);
                TestScene.LOG.info("Hovering 'Stream to players' ({}): {}", first.equals(on) ? "enabled" : "disabled", first);
                return;
            }
            TestScene.LOG.error("No 'Stream to players' check box found");
        } catch (ReflectiveOperationException e) {
            TestScene.LOG.error("hoverShare", e);
        }
    }

    private static Object call(Object target, String method) throws ReflectiveOperationException {
        var m = target.getClass().getDeclaredMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static void setMouse(Minecraft mc, double x, double y) throws ReflectiveOperationException {
        // the dev client runs with Mojang's names, on every version
        java.lang.reflect.Field fx = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos");
        java.lang.reflect.Field fy = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
        fx.setAccessible(true);
        fy.setAccessible(true);
        fx.setDouble(mc.mouseHandler, x);
        fy.setDouble(mc.mouseHandler, y);
    }

    private static String fps(Minecraft mc) {
        //#if MC >= 1.19
        return String.valueOf(mc.getFps());
        //#else
        return mc.fpsString;
        //#endif
    }

    private static void shot(Minecraft mc, String name) {
        //#if MC >= 26.2
        var target = mc.gameRenderer.mainRenderTarget();
        //#else
        var target = mc.getMainRenderTarget();
        //#endif
        //#if MC >= 1.21.11
        Screenshot.grab(mc.gameDirectory, PREFIX + "-" + name + ".png", target, 1,
        //#else
        Screenshot.grab(mc.gameDirectory, PREFIX + "-" + name + ".png", target,
        //#endif
                msg -> TestScene.LOG.info("Screenshot {}: {}", name, msg.getString()));
    }
}
