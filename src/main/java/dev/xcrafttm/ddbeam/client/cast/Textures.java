package dev.xcrafttm.ddbeam.client.cast;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
//#if MC >= 26.3
import com.mojang.renderpearl.api.textures.FilterMode;
//#elif MC >= 1.21.11
import com.mojang.blaze3d.textures.FilterMode;
//#endif
//#if MC >= 1.21.11 && MC < 26.2
import com.mojang.blaze3d.platform.NativeImage;
//#endif
//#if MC < 1.21.11
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
//#endif

import java.nio.ByteBuffer;

/**
 * Textures that Beam fills with RGBA pixels (cast pictures, previews), on every Minecraft version: through the
 * game's GPU command encoder from 1.21.11 (whose write call changed in 26.2), through OpenGL before. Linearly
 * filtered, like Dream Displays' video textures.
 */
public final class Textures {
    private Textures() {
    }

    public static DynamicTexture create(int width, int height) {
        //#if MC >= 1.21.11
        return new Linear(width, height);
        //#else
        DynamicTexture texture = new DynamicTexture(width, height, false);
        texture.setFilter(true, false);
        return texture;
        //#endif
    }

    /** Render thread. */
    public static void write(DynamicTexture texture, ByteBuffer rgba, int width, int height) {
        //#if MC >= 26.2
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture.getTexture(), rgba, 0, 0, 0, 0, width, height);
        //#elif MC >= 1.21.11
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture.getTexture(), rgba, NativeImage.Format.RGBA, 0, 0, 0, 0, width, height);
        //#else
        GlStateManager._bindTexture(texture.getId());
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
        //#endif
    }

    /** Registers the texture under a Beam id; returns that id (keep it in a {@code var}). */
    public static Object register(String path, DynamicTexture texture) {
        var id = dev.xcrafttm.ddbeam.Ids.beam(path);
        Minecraft.getInstance().getTextureManager().register(id, texture);
        return id;
    }

    public static void release(Object id) {
        //#if MC >= 1.21.11
        Minecraft.getInstance().getTextureManager().release((net.minecraft.resources.Identifier) id);
        //#else
        Minecraft.getInstance().getTextureManager().release((net.minecraft.resources.ResourceLocation) id);
        //#endif
    }

    //#if MC >= 1.21.11
    private static final class Linear extends DynamicTexture {
        Linear(int width, int height) {
            super(() -> "ddbeam", width, height, false);
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }
    //#endif
}
