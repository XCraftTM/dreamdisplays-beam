package dev.xcrafttm.ddbeam.client.compat;

import dev.xcrafttm.ddbeam.DdBeam;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Dream Displays Lumen, when it is installed: it lights the room in the colors of a display's video, which it
 * takes from Dream Displays' frame uploads. A cast never goes through those, so Beam hands its frames to Lumen's
 * {@code LumenFrames} (looked up by name: no build dependency, and a Lumen without it just stays dark).
 */
public final class Lumen {
    private static final MethodHandle SUBMIT, END;
    private static volatile boolean broken;

    static {
        MethodHandle submit = null, end = null;
        if (FabricLoader.getInstance().isModLoaded("ddlumen")) {
            try {
                Class<?> api = Class.forName("dev.xcrafttm.ddlumen.api.LumenFrames");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                submit = lookup.findStatic(api, "submit", MethodType.methodType(void.class, UUID.class, ByteBuffer.class, int.class, int.class));
                end = lookup.findStatic(api, "end", MethodType.methodType(void.class, UUID.class));
                DdBeam.LOGGER.info("Dream Displays Lumen: casts light the room too");
            } catch (ReflectiveOperationException e) {
                DdBeam.LOGGER.info("This Dream Displays Lumen is too old to light casts (it needs LumenFrames)");
            }
        }
        SUBMIT = submit;
        END = end;
    }

    private Lumen() {
    }

    /** Render thread, once per new frame: RGBA, rows top to bottom. */
    public static void frame(UUID display, ByteBuffer rgba, int width, int height) {
        if (SUBMIT == null || broken) return;
        try {
            SUBMIT.invokeExact(display, rgba, width, height);
        } catch (Throwable t) {
            off(t);
        }
    }

    /** Render thread: the cast on this display ended. */
    public static void end(UUID display) {
        if (END == null || broken) return;
        try {
            END.invokeExact(display);
        } catch (Throwable t) {
            off(t);
        }
    }

    private static void off(Throwable t) {
        broken = true;
        DdBeam.LOGGER.warn("Dream Displays Lumen failed on a cast frame, casts stay unlit: {}", t.toString());
    }
}
