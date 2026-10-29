package dev.xcrafttm.ddbeam.client.ui;

import dev.xcrafttm.ddbeam.DdBeam;
import dev.xcrafttm.ddbeam.client.capture.CaptureSource;
import dev.xcrafttm.ddbeam.client.capture.Ffmpeg;
import net.minecraft.client.Minecraft;
import dev.xcrafttm.ddbeam.client.cast.Textures;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * One still picture per source for the cast dialog: FFmpeg grabs a single frame (one source after the other, so a
 * camera is never opened twice at once), the texture is made on the render thread.
 */
final class Thumbnails {
    static final int WIDTH = 320, HEIGHT = 180;

    private static final ExecutorService GRABBER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ddbeam-thumbnails");
        thread.setDaemon(true);
        return thread;
    });
    /** Resource ids of the textures (Identifier or ResourceLocation, by Minecraft version). */
    private static final Map<String, Object> TEXTURES = new HashMap<>();
    private static final Map<String, Long> REQUESTED = new HashMap<>();
    private static int generation;

    private Thumbnails() {
    }

    /** The newest picture of this source, or null; asks for a new one when the last is older than {@code maxAgeMs}. */
    static Object get(CaptureSource source, long maxAgeMs) {
        String key = source.key();
        Long at = REQUESTED.get(key);
        long now = System.currentTimeMillis();
        if (!source.portal() && (at == null || now - at > maxAgeMs)) {
            REQUESTED.put(key, now);
            GRABBER.execute(() -> grab(source, key));
        }
        return TEXTURES.get(key);
    }

    private static void grab(CaptureSource source, String key) {
        try {
            List<String> command = new ArrayList<>();
            command.add(Ffmpeg.path());
            command.addAll(List.of("-hide_banner", "-loglevel", "error", "-nostdin"));
            command.addAll(source.inputArgs(source.kind() == CaptureSource.Kind.TEST ? 30 : 10, true));
            command.addAll(List.of("-frames:v", "1", "-an", "-vf",
                    "scale=" + WIDTH + ":" + HEIGHT + ":force_original_aspect_ratio=decrease,pad=" + WIDTH + ":" + HEIGHT
                            + ":(ow-iw)/2:(oh-ih)/2:black,format=yuvj420p",
                    "-c:v", "mjpeg", "-q:v", "4", "-f", "image2pipe", "pipe:1"));
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                in.transferTo(out);
            }
            if (!process.waitFor(8, TimeUnit.SECONDS)) process.destroyForcibly();
            byte[] jpeg = out.toByteArray();
            if (jpeg.length == 0) return;
            ByteBuffer data = MemoryUtil.memAlloc(jpeg.length);
            ByteBuffer pixels;
            int w, h;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                data.put(jpeg).flip();
                IntBuffer pw = stack.mallocInt(1), ph = stack.mallocInt(1), pc = stack.mallocInt(1);
                pixels = STBImage.stbi_load_from_memory(data, pw, ph, pc, 4);
                w = pw.get(0);
                h = ph.get(0);
            } finally {
                MemoryUtil.memFree(data);
            }
            if (pixels == null) return;
            ByteBuffer frame = pixels;
            Minecraft.getInstance().execute(() -> upload(key, frame, w, h));
        } catch (Exception e) {
            DdBeam.LOGGER.debug("No thumbnail for {}: {}", key, e.toString());
        }
    }

    private static void upload(String key, ByteBuffer pixels, int w, int h) {
        try {
            DynamicTexture texture = Textures.create(w, h);
            Textures.write(texture, pixels, w, h);
            Object id = Textures.register("thumbnail/" + (++generation), texture);
            Object old = TEXTURES.put(key, id);
            if (old != null) Textures.release(old);
        } finally {
            STBImage.stbi_image_free(pixels);
        }
    }
}
