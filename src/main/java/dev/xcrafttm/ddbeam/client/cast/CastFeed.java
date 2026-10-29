package dev.xcrafttm.ddbeam.client.cast;

import com.dreamdisplays.platform.client.render.DisplayUnlitRenderTypes;
import dev.xcrafttm.ddbeam.client.compat.Lumen;
import dev.xcrafttm.ddbeam.DdBeam;
import net.minecraft.client.Minecraft;
//#if MC >= 1.21.11
import net.minecraft.client.renderer.rendertype.RenderType;
//#else
import net.minecraft.client.renderer.RenderType;
//#endif
import net.minecraft.client.renderer.texture.DynamicTexture;

import org.lwjgl.system.MemoryUtil;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The picture of one cast on one display: JPEG frames come in from any thread, are decoded in the background
 * (always only the newest; frames that arrive while one decodes are skipped) and uploaded into a texture on the
 * render thread right before the display is drawn.
 */
public final class CastFeed {
    private static final ExecutorService DECODER = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "ddbeam-decode");
        thread.setDaemon(true);
        return thread;
    });
    private static int generation;

    public final UUID display;
    /** The player who casts, null for this player's own cast. */
    public final UUID caster;
    private final AtomicReference<byte[]> pending = new AtomicReference<>();
    private final AtomicBoolean decoding = new AtomicBoolean();
    private final AtomicReference<JpegDecoder.Decoded> ready = new AtomicReference<>();
    private volatile boolean closed;
    private volatile long lastFrameNs = System.nanoTime();
    private volatile long received, decoded, failed;
    private long shown;

    /** Parts of the other player's JPEG frame that is arriving (client thread). */
    final FrameParts parts = new FrameParts();

    /** The other player's H.264 stream, decoded by FFmpeg (client thread). */
    StreamDecoder decoder;

    // render thread only
    private DynamicTexture texture;
    private Object textureId;
    private RenderType renderType;
    private int width, height;

    public CastFeed(UUID display, UUID caster) {
        this.display = display;
        this.caster = caster;
    }

    /** A new frame. Any thread. */
    public void submit(byte[] jpeg) {
        if (closed) return;
        lastFrameNs = System.nanoTime();
        received++;
        pending.set(jpeg);
        schedule();
    }

    /** A frame decoded elsewhere (the H.264 stream), RGBA in memory from MemoryUtil.memAlloc. Any thread. */
    public void submitDecoded(java.nio.ByteBuffer rgba, int width, int height) {
        if (closed) {
            MemoryUtil.memFree(rgba);
            return;
        }
        lastFrameNs = System.nanoTime();
        received++;
        decoded++;
        JpegDecoder.Decoded old = ready.getAndSet(new JpegDecoder.Decoded(rgba, width, height, false));
        if (old != null) old.free();
        if (closed) {
            JpegDecoder.Decoded left = ready.getAndSet(null);
            if (left != null) left.free();
        }
    }

    /** Stream data arrived: the cast is alive even while the decoder still waits for a key frame. */
    public void touch() {
        lastFrameNs = System.nanoTime();
    }

    private void schedule() {
        if (!closed && decoding.compareAndSet(false, true)) DECODER.execute(this::drain);
    }

    private void drain() {
        try {
            byte[] jpeg;
            while (!closed && (jpeg = pending.getAndSet(null)) != null) {
                JpegDecoder.Decoded frame = JpegDecoder.decode(jpeg);
                if (frame == null) {
                    failed++;
                    continue;
                }
                decoded++;
                JpegDecoder.Decoded old = ready.getAndSet(frame);
                if (old != null) old.free();
            }
        } catch (RuntimeException e) {
            failed++;
            DdBeam.LOGGER.warn("Decoding a cast frame failed: {}", e.toString());
        } finally {
            decoding.set(false);
            if (closed) {
                JpegDecoder.Decoded left = ready.getAndSet(null);
                if (left != null) left.free();
            } else if (pending.get() != null) {
                schedule();
            }
        }
    }

    /** Uploads the newest frame and returns what draws it; null until the first frame is there. Render thread. */
    public RenderType prepare() {
        JpegDecoder.Decoded frame = ready.getAndSet(null);
        if (frame != null) {
            try {
                upload(frame);
            } catch (RuntimeException e) {
                DdBeam.LOGGER.warn("Uploading a cast frame failed: {}", e.toString());
            } finally {
                frame.free();
            }
        }
        return renderType;
    }

    private void upload(JpegDecoder.Decoded frame) {
        if (texture == null || width != frame.width() || height != frame.height()) {
            releaseTexture();
            width = frame.width();
            height = frame.height();
            texture = Textures.create(width, height);
            var id = dev.xcrafttm.ddbeam.Ids.beam("cast/" + display + "/" + (++generation));
            Minecraft.getInstance().getTextureManager().register(id, texture);
            textureId = id;
            renderType = DisplayUnlitRenderTypes.INSTANCE.create("ddbeam_cast", id);
        }
        Textures.write(texture, frame.pixels(), width, height);
        // Dream Displays Lumen lights the room in the cast's colors
        Lumen.frame(display, frame.pixels(), width, height);
        shown++;
    }

    private void releaseTexture() {
        if (textureId != null) Textures.release(textureId);
        texture = null;
        textureId = null;
        renderType = null;
    }

    public boolean hasPicture() {
        return renderType != null || ready.get() != null;
    }

    public long lastFrameNs() {
        return lastFrameNs;
    }

    public String stats() {
        return received + " / " + decoded + " / " + shown + (failed > 0 ? " (" + failed + " bad)" : "") + ", " + width + "x" + height;
    }

    public long shown() {
        return shown;
    }

    /** Frees everything; call on the render thread. */
    public void close() {
        closed = true;
        StreamDecoder d = decoder;
        decoder = null;
        if (d != null) d.close();
        pending.set(null);
        JpegDecoder.Decoded left = ready.getAndSet(null);
        if (left != null) left.free();
        releaseTexture();
        Lumen.end(display);
    }

    /** Puts a JPEG frame together from its parts; a frame with a part missing is dropped. */
    static final class FrameParts {
        private int seq = -1, next;
        private java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();

        /** The whole frame once its last part arrived, else null. */
        byte[] add(int seq, int part, int parts, byte[] bytes) {
            if (parts <= 0 || part >= parts || parts > dev.xcrafttm.ddbeam.net.BeamPayloads.MAX_FRAME_BYTES / dev.xcrafttm.ddbeam.net.BeamPayloads.MAX_PART + 1) return null;
            if (part == 0) {
                this.seq = seq;
                next = 0;
                data.reset();
            }
            if (seq != this.seq || part != next) return null;
            data.write(bytes, 0, bytes.length);
            next++;
            if (next < parts) return null;
            this.seq = -1;
            return data.toByteArray();
        }
    }
}
