package dev.xcrafttm.ddbeam.client.ui;

import dev.xcrafttm.ddbeam.Ids;
import dev.xcrafttm.ddbeam.Texts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
//#if MC >= 1.21.11
import net.minecraft.resources.Identifier;
//#else
import net.minecraft.resources.ResourceLocation;
//#endif
//#if MC >= 26.1
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
//#elif MC >= 1.21.11
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
//#elif MC >= 1.20
import net.minecraft.client.gui.GuiGraphics;
//#else
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
//#endif

import java.util.List;

/**
 * The cast dialog's drawing, the same calls on every Minecraft version: vanilla's button and check box look, Dream
 * Displays' panel, text, pictures, clipping and scaling. Minecraft's GUI drawing changed several times (26.x
 * GuiGraphicsExtractor, 1.20 – 1.21 GuiGraphics with and without sprites, PoseStack before); this class holds all
 * of it.
 */
public final class Gfx {
    /** Tooltip lines wrap at this width, like vanilla's wrapped tooltips. */
    private static final int TOOLTIP_WIDTH = 220;
    //#if MC >= 26.1
    private final GuiGraphicsExtractor g;
    //#elif MC >= 1.20
    private final GuiGraphics g;
    //#else
    private final PoseStack g;
    //#endif
    private final Font font = Minecraft.getInstance().font;
    /** Virtual pixels per GUI pixel (the dialog draws at a finer scale on small screens). */
    private float k = 1;

    //#if MC >= 26.1
    public Gfx(GuiGraphicsExtractor g) {
    //#elif MC >= 1.20
    public Gfx(GuiGraphics g) {
    //#else
    public Gfx(PoseStack g) {
    //#endif
        this.g = g;
    }

    /** Side of a check box. */
    public static int checkboxSize() {
        //#if MC >= 1.20.2
        return 17;
        //#else
        return 20;
        //#endif
    }

    public Font font() {
        return font;
    }

    public void fill(int x0, int y0, int x1, int y1, int color) {
        //#if MC >= 1.20
        g.fill(x0, y0, x1, y1, color);
        //#else
        GuiComponent.fill(g, x0, y0, x1, y1, color);
        //#endif
    }

    public void outline(int x, int y, int w, int h, int color) {
        fill(x, y, x + w, y + 1, color);
        fill(x, y + h - 1, x + w, y + h, color);
        fill(x, y + 1, x + 1, y + h - 1, color);
        fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    public void text(Component text, int x, int y, int color, boolean shadow) {
        //#if MC >= 26.1
        g.text(font, text, x, y, color, shadow);
        //#elif MC >= 1.20
        g.drawString(font, text, x, y, color, shadow);
        //#else
        if (shadow) font.drawShadow(g, text, x, y, color);
        else font.draw(g, text, x, y, color);
        //#endif
    }

    public int width(Component text) {
        return font.width(text);
    }

    public int width(String text) {
        return font.width(text);
    }

    /** Vanilla's button background. */
    public void button(int x, int y, int w, int h, boolean hovered, boolean active) {
        String sprite = !active ? "widget/button_disabled" : hovered ? "widget/button_highlighted" : "widget/button";
        //#if MC >= 1.21.11
        g.blitSprite(RenderPipelines.GUI_TEXTURED, Ids.vanilla(sprite), x, y, w, h);
        //#elif MC >= 1.20.2
        g.blitSprite(Ids.vanilla(sprite), x, y, w, h);
        //#else
        int v = 46 + (!active ? 0 : hovered ? 40 : 20);
        //#if MC >= 1.20
        g.blitNineSliced(Ids.vanilla("textures/gui/widgets.png"), x, y, w, h, 20, 4, 200, 20, 0, v);
        //#else
        RenderSystem.setShaderTexture(0, Ids.vanilla("textures/gui/widgets.png"));
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableBlend();
        nineSliced(x, y, w, h, 20, 4, 200, 20, 0, v);
        //#endif
        //#endif
    }

    //#if MC < 1.20
    /**
     * Like 1.20's {@code blitNineSliced} on a 256 x 256 texture: the corners as they are, edges and middle stretched,
     * so a button background can be any size (tiles are much taller than the 20 pixels of a button).
     */
    private void nineSliced(int x, int y, int w, int h, int cornerW, int cornerH, int uW, int vH, int u, int v) {
        int cw = Math.min(cornerW, w / 2), ch = Math.min(cornerH, h / 2);
        int[] xs = {x, x + cw, x + w - cw}, ws = {cw, w - 2 * cw, cw};
        int[] us = {u, u + cw, u + uW - cw}, uws = {cw, uW - 2 * cw, cw};
        int[] ys = {y, y + ch, y + h - ch}, hs = {ch, h - 2 * ch, ch};
        int[] vs = {v, v + ch, v + vH - ch}, vhs = {ch, vH - 2 * ch, ch};
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                if (ws[col] <= 0 || hs[row] <= 0) continue;
                GuiComponent.blit(g, xs[col], ys[row], ws[col], hs[row], us[col], vs[row], uws[col], vhs[row], 256, 256);
            }
        }
    }
    //#endif

    /** Vanilla's check box. */
    public void checkbox(int x, int y, boolean checked, boolean hovered) {
        //#if MC >= 1.20.2
        String sprite = "widget/checkbox" + (checked ? "_selected" : "") + (hovered ? "_highlighted" : "");
        int s = checkboxSize();
        //#if MC >= 1.21.11
        g.blitSprite(RenderPipelines.GUI_TEXTURED, Ids.vanilla(sprite), x, y, s, s);
        //#else
        g.blitSprite(Ids.vanilla(sprite), x, y, s, s);
        //#endif
        //#else
        image(Ids.vanilla("textures/gui/checkbox.png"), x, y, hovered ? 20 : 0, checked ? 20 : 0, 20, 20, 64, 64);
        //#endif
    }

    /** Dream Displays' panel. */
    public void panel(int x, int y, int w, int h) {
        //#if MC >= 1.21.11
        g.blitSprite(RenderPipelines.GUI_TEXTURED, Ids.of("dreamdisplays", "widgets/panel"), x, y, w, h);
        //#elif MC >= 1.20.2
        g.blitSprite(Ids.of("dreamdisplays", "widgets/panel"), x, y, w, h);
        //#else
        // the same look: a translucent dark inside, a grey border
        fill(x + 1, y + 1, x + w - 1, y + h - 1, 0x90101010);
        outline(x, y, w, h, 0xFF606060);
        //#endif
    }

    /** A whole texture (a resource id from {@link Ids}), stretched over the rectangle. */
    public void image(Object id, int x, int y, int w, int h) {
        //#if MC >= 1.21.11
        image((Identifier) id, x, y, 0, 0, w, h, w, h);
        //#else
        image((ResourceLocation) id, x, y, 0, 0, w, h, w, h);
        //#endif
    }

    /** Part (u, v, w, h) of a texture of size texW x texH. */
    //#if MC >= 1.21.11
    private void image(Identifier id, int x, int y, int u, int v, int w, int h, int texW, int texH) {
    //#else
    private void image(ResourceLocation id, int x, int y, int u, int v, int w, int h, int texW, int texH) {
    //#endif
        //#if MC >= 1.21.11
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, w, h, texW, texH);
        //#elif MC >= 1.20
        g.blit(id, x, y, (float) u, (float) v, w, h, texW, texH);
        //#else
        RenderSystem.setShaderTexture(0, id);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableBlend();
        GuiComponent.blit(g, x, y, (float) u, (float) v, w, h, texW, texH);
        //#endif
    }

    /** A tooltip box like vanilla's, kept on screen. */
    public void tooltip(List<Component> text, int mouseX, int mouseY, int screenW, int screenH) {
        if (text.isEmpty()) return;
        // long lines wrapped, each keeping its colour
        List<Component> lines = new java.util.ArrayList<>();
        for (Component line : text) {
            for (var part : font.getSplitter().splitLines(line, TOOLTIP_WIDTH, line.getStyle())) {
                lines.add(Texts.lit(part.getString()).setStyle(line.getStyle()));
            }
        }
        int w = 0;
        for (Component line : lines) w = Math.max(w, width(line));
        int h = lines.size() * 10 - 2;
        int x = mouseX + 12, y = mouseY - 12;
        if (x + w + 4 > screenW) x = Math.max(4, mouseX - 16 - w);
        if (y + h + 4 > screenH) y = screenH - h - 4;
        y = Math.max(4, y);
        top();
        fill(x - 3, y - 4, x + w + 3, y + h + 4, 0xF0100010);
        fill(x - 4, y - 3, x + w + 4, y + h + 3, 0xF0100010);
        outline(x - 3, y - 3, w + 6, h + 6, 0x505000FF);
        for (int i = 0; i < lines.size(); i++) text(lines.get(i), x, y + i * 10, 0xFFFFFFFF, true);
    }

    /** Draw at {@code 1 / k} of the GUI scale until {@link #popScale()}. */
    public void pushScale(float k) {
        this.k = k;
        float s = 1 / k;
        //#if MC >= 1.21.11
        g.pose().pushMatrix();
        g.pose().scale(s, s);
        //#elif MC >= 1.20
        g.pose().pushPose();
        g.pose().scale(s, s, 1);
        //#else
        g.pushPose();
        g.scale(s, s, 1);
        //#endif
    }

    public void popScale() {
        //#if MC >= 1.21.11
        g.pose().popMatrix();
        //#elif MC >= 1.20
        g.pose().popPose();
        //#else
        g.popPose();
        //#endif
        k = 1;
    }

    /** Clips to a rectangle in the current (virtual) coordinates. */
    public void scissor(int x0, int y0, int x1, int y1) {
        //#if MC >= 1.21.11
        // the scissor follows the pose here
        g.enableScissor(x0, y0, x1, y1);
        //#elif MC >= 1.20
        g.enableScissor(Math.round(x0 / k), Math.round(y0 / k), Math.round(x1 / k), Math.round(y1 / k));
        //#else
        var window = Minecraft.getInstance().getWindow();
        double s = window.getGuiScale() / k;
        RenderSystem.enableScissor((int) (x0 * s), (int) (window.getHeight() - y1 * s), (int) ((x1 - x0) * s), (int) ((y1 - y0) * s));
        //#endif
    }

    public void endScissor() {
        //#if MC >= 1.20
        g.disableScissor();
        //#else
        RenderSystem.disableScissor();
        //#endif
    }

    /** What is drawn after this lies over everything drawn before (menus, tooltips). */
    public void top() {
        //#if MC >= 1.21.11
        g.nextStratum();
        //#elif MC >= 1.20
        g.pose().translate(0, 0, 400);
        //#else
        g.translate(0, 0, 400);
        //#endif
    }
}
