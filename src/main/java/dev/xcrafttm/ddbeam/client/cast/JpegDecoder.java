package dev.xcrafttm.ddbeam.client.cast;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** Decodes JPEG frames to RGBA with stb_image (bundled with the game). Thread safe. */
final class JpegDecoder {
    /** Frames from other players are refused above this size, before decoding. */
    static final int MAX_SIDE = 3840;

    private JpegDecoder() {
    }

    /** A decoded RGBA frame in native memory; {@link #free()} it once uploaded. */
    record Decoded(ByteBuffer pixels, int width, int height, boolean fromStb) {
        Decoded(ByteBuffer pixels, int width, int height) {
            this(pixels, width, height, true);
        }

        void free() {
            if (fromStb) STBImage.stbi_image_free(pixels);
            else MemoryUtil.memFree(pixels);
        }
    }

    /** Null when the data is no picture or too large. */
    static Decoded decode(byte[] jpeg) {
        ByteBuffer data = MemoryUtil.memAlloc(jpeg.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            data.put(jpeg).flip();
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), channels = stack.mallocInt(1);
            if (!STBImage.stbi_info_from_memory(data, w, h, channels)) return null;
            if (w.get(0) <= 0 || h.get(0) <= 0 || w.get(0) > MAX_SIDE || h.get(0) > MAX_SIDE) return null;
            ByteBuffer pixels = STBImage.stbi_load_from_memory(data, w, h, channels, 4);
            return pixels == null ? null : new Decoded(pixels, w.get(0), h.get(0));
        } finally {
            MemoryUtil.memFree(data);
        }
    }
}
