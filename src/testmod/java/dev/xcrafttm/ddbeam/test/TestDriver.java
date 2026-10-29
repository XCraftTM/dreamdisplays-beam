package dev.xcrafttm.ddbeam.test;

import com.dreamdisplays.platform.client.displays.DisplayScreen;
import com.dreamdisplays.platform.client.ui.DisplayMenu;
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
 *   <li>{@code viewer}: joins it (see the viewer run in build.gradle), shoots the other player's cast.</li>
 * </ul>
 */
public final class TestDriver implements ClientModInitializer {
    private static final String MODE = System.getProperty("ddbeam.test.mode", "cast");
    private static final String SOURCE = System.getProperty("ddbeam.test.source", "test");
    private static final String PREFIX = System.getProperty("ddbeam.test.prefix", "beam");
    private static final long TIMEOUT_MS = 240_000;

    private long started, sceneAt, castAt, stepAt, doneAt;
    private int step;
    private boolean viewerSeen;

    @Override
    public void onInitializeClient() {
        if (System.getProperty("ddbeam.test.mode") == null) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

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
        Targets.Target target = targets.getFirst();
        if (MODE.equals("click")) {
            clickTick(mc, now, target);
            return;
        }
        if (castAt == 0) {
            if (MODE.equals("stream")) {
                boolean lan = mc.getSingleplayerServer() != null
                        && mc.getSingleplayerServer().publishServer(MinecraftServer.MultiplayerScope.LAN, true, 25700);
                TestScene.LOG.info("Open to LAN on 25700: {}", lan);
            }
            CaptureSource source = source(mc);
            if (source == null) {
                TestScene.LOG.error("No source {}", SOURCE);
                doneAt = now;
                return;
            }
            TestScene.LOG.info("Casting {} onto {}", source.label().getString(), target.id());
            Casts.start(target, new LocalCast.Settings(source, 1280, 720, 30, true, true));
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
                if (feed == null || feed.shown() < 30) return;
                status();
                shot(mc, "cast");
                step++;
                stepAt = now;
            }
            case 1 -> {
                // look at it from the side too (Dream Displays' renderer draws it in perspective)
                if (now - stepAt < 500) return;
                mc.player.snapTo(5.5, -60, 6.5, 145, 5);
                step++;
                stepAt = now;
            }
            case 2 -> {
                if (now - stepAt < 1500) return;
                shot(mc, "cast-side");
                mc.player.snapTo(0.5, -60, 11.5, 180, 2);
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
                Screen menu = mc.gui.screen();
                mc.setScreenAndShow(new BeamScreen(menu, target));
                step++;
                stepAt = now;
            }
            case 4 -> {
                if (now - stepAt < 5000) return;
                shot(mc, "dialog");
                if (mc.gui.screen() instanceof BeamScreen dialog) dialog.openSelect(0);
                step = 40;
                stepAt = now;
            }
            case 40 -> {
                if (now - stepAt < 800) return;
                shot(mc, "dialog-select");
                if (mc.gui.screen() instanceof BeamScreen dialog) {
                    dialog.openSelect(-1);
                    dialog.showTab(2);
                }
                step = 5;
                stepAt = now;
            }
            case 5 -> {
                if (now - stepAt < 9000) return;
                shot(mc, "dialog-cameras");
                mc.setScreenAndShow(null);
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

    /** Opens the dialog and waits for real mouse clicks (driven from outside), logging what is chosen. */
    private void clickTick(Minecraft mc, long now, Targets.Target target) {
        if (castAt == 0) {
            mc.setScreenAndShow(new BeamScreen(null, target));
            castAt = now;
            stepAt = now;
            TestScene.LOG.info("Dialog open, waiting for clicks");
            return;
        }
        if (now - stepAt < 1000) return;
        stepAt = now;
        Object selected = null;
        if (mc.gui.screen() instanceof BeamScreen dialog) {
            try {
                var field = BeamScreen.class.getDeclaredField("selected");
                field.setAccessible(true);
                selected = field.get(dialog);
            } catch (ReflectiveOperationException e) {
                selected = e.toString();
            }
        }
        TestScene.LOG.info("Click test: screen {}, selected {}, casting {}",
                mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName(), selected,
                Casts.local() == null ? "no" : Casts.local().source.label().getString());
        if (now - castAt > 90_000 || (Casts.local() != null && now - castAt > 3000 && mc.gui.screen() == null)) {
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
            if (now - stepAt < 4000) return;
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
        LocalCast cast = Casts.local();
        if (cast != null) {
            TestScene.LOG.info("Local cast: {} {}x{} @{}, {} frames, shared {}, audio '{}'", cast.source.label().getString(),
                    cast.width, cast.height, cast.settings.fps(), cast.frames(), cast.sharedWithOthers(), cast.audioKind());
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

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, PREFIX + "-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
                msg -> TestScene.LOG.info("Screenshot {}: {}", name, msg.getString()));
    }
}
