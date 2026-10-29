package dev.xcrafttm.ddbeam.client.ui;

import dev.xcrafttm.ddbeam.client.BeamConfig;
import dev.xcrafttm.ddbeam.client.capture.CaptureSource;
import dev.xcrafttm.ddbeam.client.capture.Sources;
import dev.xcrafttm.ddbeam.client.cast.Casts;
import dev.xcrafttm.ddbeam.client.cast.LocalCast;
import dev.xcrafttm.ddbeam.client.cast.Targets;
import dev.xcrafttm.ddbeam.net.BeamPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The cast dialog, laid out like a chat app's "go live" dialog (source tabs, preview tiles, stream quality, go
 * live) but made of Minecraft's own buttons and check boxes and Dream Displays' panels, so it sits well next to
 * Dream Displays' menu.
 */
public final class BeamScreen extends Screen {
    private enum Tab { SCREENS, WINDOWS, CAMERAS }

    private static final int[][] RESOLUTIONS = {{854, 480}, {1280, 720}, {1920, 1080}, {2560, 1440}};
    private static final String[] RESOLUTION_LABELS = {"480p", "720p", "1080p", "1440p"};
    private static final int[] FRAME_RATES = {15, 30, 60};

    // Dream Displays' look (its UiTheme)
    private static final Identifier PANEL = Identifier.fromNamespaceAndPath("dreamdisplays", "widgets/panel");
    private static final Identifier BUTTON = Identifier.withDefaultNamespace("widget/button");
    private static final Identifier BUTTON_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/button_highlighted");
    private static final int PAD = 10, GAP = 8, ROW = 20;
    private static final int TEXT = 0xFFFFFFFF, SECONDARY = 0xFFAAAAAA, ROW_BG = 0x40000000, CARD_BORDER = 0xFFE8E8E8;
    private static final int PLACEHOLDER = 0xFF1C1C1C, SCROLL_TRACK = 0xFF202020, SCROLL_THUMB = 0xFF808080, LIVE = 0xFF55FF55;

    private record Rect(int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record Tile(Rect rect, CaptureSource source) {
    }

    private final Screen parent;
    private final Targets.Target target;
    private final List<Tile> tiles = new ArrayList<>();
    private Tab tab = Tab.SCREENS;
    private List<CaptureSource> monitors = List.of(), windows = List.of(), cameras;
    private CaptureSource selected;
    private int resolution, frameRate;
    private boolean share, audio;
    private double scroll, maxScroll;
    private Rect sourcePanel, grid, qualityPanel;
    private Button goButton;

    public BeamScreen(Screen parent, Targets.Target target) {
        super(Component.translatable("ddbeam.dialog.title"));
        this.parent = parent;
        this.target = target;
        BeamConfig config = BeamConfig.get();
        resolution = 1;
        for (int i = 0; i < RESOLUTIONS.length; i++) if (RESOLUTIONS[i][1] == config.maxHeight) resolution = i;
        frameRate = 1;
        for (int i = 0; i < FRAME_RATES.length; i++) if (FRAME_RATES[i] == config.fps) frameRate = i;
        share = config.share;
        audio = config.systemAudio;
        monitors = new ArrayList<>(Sources.monitors());
        monitors.add(CaptureSource.test());
        windows = Sources.windows();
        cameras = Sources.cameras();
        Sources.refresh().thenAccept(found -> Minecraft.getInstance().execute(() -> cameras = found));
        LocalCast current = Casts.local();
        if (current != null && current.display.equals(target.id())) {
            selected = current.source;
            tab = switch (selected.kind()) {
                case CAMERA -> Tab.CAMERAS;
                case WINDOW -> Tab.WINDOWS;
                default -> Tab.SCREENS;
            };
        }
    }

    private boolean live() {
        LocalCast current = Casts.local();
        return current != null && current.display.equals(target.id());
    }

    @Override
    protected void init() {
        // like Dream Displays' menu: a finer GUI scale when the chosen one leaves too little room (whole scales only,
        // so the text stays sharp)
        int guiScale = minecraft.getWindow().getGuiScale();
        int fit = (int) Math.min(minecraft.getWindow().getWidth() / (double) MIN_W, minecraft.getWindow().getHeight() / (double) MIN_H);
        k = guiScale / (float) Math.clamp(fit, 1, guiScale);
        vw = Math.round(width * k);
        vh = Math.round(height * k);
        boolean serverRelays = ClientPlayNetworking.canSend(BeamPayloads.Start.TYPE);
        Component resLabel = Component.translatable("ddbeam.dialog.resolution");
        Component fpsLabel = Component.translatable("ddbeam.dialog.fps");
        Component shareLabel = Component.translatable("ddbeam.dialog.share");
        String audioKey = selected == null ? "ddbeam.dialog.audio" : switch (selected.kind()) {
            case CAMERA, TEST -> "ddbeam.dialog.audio.camera";
            case WINDOW -> "ddbeam.dialog.audio.window";
            default -> "ddbeam.dialog.audio.screen";
        };
        Component audioLabel = Component.translatable(audioKey);
        int selectsW = font.width(resLabel) + 6 + RES_SELECT_W + 14 + font.width(fpsLabel) + 6 + FPS_SELECT_W;
        int boxesW = 17 + 4 + font.width(shareLabel) + 16 + 17 + 4 + font.width(audioLabel);
        // the check boxes next to the select menus when there is room, else in a row of their own
        boolean oneRow = selectsW + 20 + boxesW <= vw - 4 * PAD;
        int rows = oneRow ? 1 : 2;
        int footerY = vh - PAD - ROW;
        int qualityH = PAD + font.lineHeight + 6 + rows * ROW + (rows - 1) * 4 + PAD;
        qualityPanel = new Rect(PAD, footerY - GAP - qualityH, vw - 2 * PAD, qualityH);
        sourcePanel = new Rect(PAD, 20, vw - 2 * PAD, qualityPanel.y() - GAP - 20);
        int inner = sourcePanel.x() + PAD;

        // source tabs
        int tx = inner, ty = sourcePanel.y() + PAD + font.lineHeight + 6;
        for (Tab t : Tab.values()) {
            if (t == Tab.WINDOWS && windows.isEmpty()) continue;
            Component label = Component.translatable("ddbeam.dialog.tab." + t.name().toLowerCase(Locale.ROOT));
            int w = Math.max(70, font.width(label) + 16);
            Button b = addRenderableWidget(Button.builder(label, btn -> {
                tab = t;
                scroll = 0;
            }).bounds(tx, ty, w, ROW).build());
            b.setOverrideRenderHighlightedSprite(() -> tab == t);
            tx += w + 4;
        }
        grid = new Rect(inner, ty + ROW + 6, sourcePanel.w() - 2 * PAD - 6, sourcePanel.y() + sourcePanel.h() - PAD - (ty + ROW + 6));

        // stream quality: select menus for resolution and frame rate
        selects.clear();
        openSelect = null;
        int qx = qualityPanel.x() + PAD, qy = qualityPanel.y() + PAD + font.lineHeight + 6;
        resLabelX = qx;
        int x = qx + font.width(resLabel) + 6;
        selects.add(new Select(x, qy, RES_SELECT_W, RESOLUTION_LABELS, () -> resolution, i -> resolution = i));
        fpsLabelX = x + RES_SELECT_W + 14;
        x = fpsLabelX + font.width(fpsLabel) + 6;
        String[] rates = new String[FRAME_RATES.length];
        for (int i = 0; i < rates.length; i++) rates[i] = Component.translatable("ddbeam.dialog.fps.value", FRAME_RATES[i]).getString();
        selects.add(new Select(x, qy, FPS_SELECT_W, rates, () -> frameRate, i -> frameRate = i));
        selectsY = qy;

        // stream to players / audio
        int cx = oneRow ? x + FPS_SELECT_W + 20 : qx, cy = oneRow ? qy : qy + ROW + 4;
        Checkbox shareBox = addRenderableWidget(Checkbox.builder(shareLabel, font)
                .pos(cx, cy + (ROW - 17) / 2).selected(share && serverRelays).onValueChange((box, value) -> share = value).build());
        shareBox.active = serverRelays;
        addRenderableWidget(Checkbox.builder(audioLabel, font)
                .pos(cx + shareBox.getWidth() + 16, cy + (ROW - 17) / 2).selected(audio).onValueChange((box, value) -> audio = value).build());

        // footer: cancel, stop, go live
        int bx = vw - PAD;
        Component goLabel = Component.translatable(live() ? "ddbeam.dialog.switch" : "ddbeam.dialog.go");
        bx -= 100;
        goButton = addRenderableWidget(Button.builder(goLabel, btn -> goLive()).bounds(bx, footerY, 100, ROW).build());
        if (live()) {
            bx -= 84;
            addRenderableWidget(Button.builder(Component.translatable("ddbeam.dialog.stop").withStyle(ChatFormatting.RED), btn -> {
                Casts.stop();
                onClose();
            }).bounds(bx, footerY, 80, ROW).build());
        }
        bx -= 84;
        addRenderableWidget(Button.builder(Component.translatable("ddbeam.dialog.cancel"), btn -> onClose()).bounds(bx, footerY, 80, ROW).build());
        footerTextRight = bx - 8;
    }

    private static final int MIN_W = 560, MIN_H = 320;
    /** Virtual size of the dialog and how many virtual pixels one GUI pixel is. */
    private float k = 1;
    private int vw, vh;
    private static final int RES_SELECT_W = 80, FPS_SELECT_W = 74;
    private int resLabelX, fpsLabelX, selectsY;

    /** A select menu: a button showing the choice, opening the list of choices under (or over) it. */
    private final class Select {
        final String[] labels;
        final java.util.function.IntSupplier get;
        final java.util.function.IntConsumer set;
        final Button button;

        Select(int x, int y, int w, String[] labels, java.util.function.IntSupplier get, java.util.function.IntConsumer set) {
            this.labels = labels;
            this.get = get;
            this.set = set;
            this.button = addRenderableWidget(Button.builder(Component.literal(labels[get.getAsInt()] + "  \u25BC"),
                    b -> openSelect = openSelect == this ? null : this).bounds(x, y, w, ROW).build());
            button.setOverrideRenderHighlightedSprite(() -> openSelect == this);
        }

        /** Item i of the open list: under the button, or over it when the screen ends first. */
        Rect item(int i) {
            boolean up = button.getY() + ROW + labels.length * ROW > vh - 4;
            int y = up ? button.getY() - (labels.length - i) * ROW : button.getY() + ROW + i * ROW;
            return new Rect(button.getX(), y, button.getWidth(), ROW);
        }

        boolean inList(double mx, double my) {
            for (int i = 0; i < labels.length; i++) if (item(i).contains(mx, my)) return true;
            return false;
        }

        void pick(int i) {
            set.accept(i);
            button.setMessage(Component.literal(labels[i] + "  \u25BC"));
            openSelect = null;
        }
    }

    private final List<Select> selects = new ArrayList<>();
    private Select openSelect;
    private int footerTextRight;

    /** Shows the tab by its number (0 screens, 1 applications, 2 cameras). */
    public void showTab(int index) {
        tab = Tab.values()[Math.clamp(index, 0, Tab.values().length - 1)];
        scroll = 0;
    }

    /** Opens select menu i (0 resolution, 1 frame rate), or closes it with -1. */
    public void openSelect(int index) {
        openSelect = index >= 0 && index < selects.size() ? selects.get(index) : null;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    private List<CaptureSource> sources() {
        return switch (tab) {
            case SCREENS -> monitors;
            case WINDOWS -> windows;
            case CAMERAS -> cameras == null ? List.of() : cameras;
        };
    }

    private void select(CaptureSource source) {
        boolean kindChanged = selected == null || selected.kind() != source.kind();
        selected = source;
        if (kindChanged) {
            BeamConfig config = BeamConfig.get();
            audio = source.kind() == CaptureSource.Kind.CAMERA ? !"none".equalsIgnoreCase(config.cameraAudio) : config.systemAudio;
            // the audio check box names what it takes
            rebuildWidgets();
        }
    }

    // ---- drawing ----

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        extractTransparentBackground(g);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int realMouseX, int realMouseY, float partialTick) {
        int mouseX = (int) (realMouseX * k), mouseY = (int) (realMouseY * k);
        g.pose().pushMatrix();
        g.pose().scale(1 / k, 1 / k);
        g.text(font, title, PAD, 6, TEXT, false);
        Component size = Component.translatable("ddbeam.dialog.display", target.width(), target.height());
        g.text(font, size, vw - PAD - font.width(size), 6, SECONDARY, false);
        panel(g, sourcePanel, Component.translatable("ddbeam.dialog.source"));
        panel(g, qualityPanel, Component.translatable("ddbeam.dialog.quality"));
        drawTiles(g, mouseX, mouseY);

        g.text(font, Component.translatable("ddbeam.dialog.resolution"), resLabelX, selectsY + 6, SECONDARY, false);
        g.text(font, Component.translatable("ddbeam.dialog.fps"), fpsLabelX, selectsY + 6, SECONDARY, false);

        goButton.active = selected != null;
        int footerY = vh - PAD - ROW + 6;
        if (live()) {
            g.text(font, Component.translatable("ddbeam.dialog.live"), PAD, footerY, LIVE, false);
        } else {
            boolean serverRelays = ClientPlayNetworking.canSend(BeamPayloads.Start.TYPE);
            Component who = Component.translatable(!serverRelays ? "ddbeam.dialog.who.local"
                    : share ? "ddbeam.dialog.who.everyone" : "ddbeam.dialog.who.you");
            int room = footerTextRight - PAD;
            if (room > 40) g.text(font, Component.literal(font.plainSubstrByWidth(who.getString(), room)), PAD, footerY, SECONDARY, false);
        }
        Select open = openSelect;
        // widgets under an open list are not hovered
        boolean overList = open != null && open.inList(mouseX, mouseY);
        super.extractRenderState(g, overList ? -1 : mouseX, overList ? -1 : mouseY, partialTick);
        if (open != null) {
            g.nextStratum();
            for (int i = 0; i < open.labels.length; i++) {
                Rect r = open.item(i);
                boolean chosen = i == open.get.getAsInt(), hover = r.contains(mouseX, mouseY);
                g.blitSprite(RenderPipelines.GUI_TEXTURED, hover ? BUTTON_HIGHLIGHTED : BUTTON, r.x(), r.y(), r.w(), r.h());
                Component label = Component.literal(open.labels[i]);
                g.text(font, label, r.x() + r.w() / 2 - font.width(label) / 2, r.y() + 6, chosen ? 0xFFFFFF55 : TEXT, true);
            }
        }
        g.pose().popMatrix();
    }

    private void panel(GuiGraphicsExtractor g, Rect r, Component title) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, r.x(), r.y(), r.w(), r.h());
        g.text(font, title, r.x() + PAD, r.y() + PAD, TEXT, false);
    }

    private void drawTiles(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        tiles.clear();
        List<CaptureSource> list = sources();
        if (list.isEmpty()) {
            Component empty = Component.translatable(tab == Tab.CAMERAS && cameras == null ? "ddbeam.dialog.searching"
                    : tab == Tab.CAMERAS ? "ddbeam.dialog.no_cameras" : "ddbeam.dialog.no_sources");
            g.text(font, empty, grid.x() + grid.w() / 2 - font.width(empty) / 2, grid.y() + grid.h() / 2 - 4, SECONDARY, false);
            return;
        }
        int cols = Math.max(1, Math.min(4, grid.w() / 150));
        int tileW = (grid.w() - GAP * (cols - 1)) / cols, thumbH = (tileW - 8) * 9 / 16, tileH = thumbH + 8 + font.lineHeight + 6;
        int rows = (list.size() + cols - 1) / cols;
        maxScroll = Math.max(0, rows * (tileH + GAP) - GAP - grid.h());
        scroll = Math.clamp(scroll, 0, maxScroll);
        boolean overGrid = grid.contains(mouseX, mouseY);
        g.enableScissor(grid.x() - 1, grid.y() - 1, grid.x() + grid.w() + 1, grid.y() + grid.h() + 1);
        for (int i = 0; i < list.size(); i++) {
            CaptureSource source = list.get(i);
            Rect r = new Rect(grid.x() + (i % cols) * (tileW + GAP), grid.y() + (i / cols) * (tileH + GAP) - (int) scroll, tileW, tileH);
            if (r.y() + tileH < grid.y() || r.y() > grid.y() + grid.h()) continue;
            boolean isSelected = selected != null && selected.key().equals(source.key());
            boolean hover = overGrid && r.contains(mouseX, mouseY);
            if (isSelected) g.outline(r.x() - 1, r.y() - 1, r.w() + 2, r.h() + 2, CARD_BORDER);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, hover || isSelected ? BUTTON_HIGHLIGHTED : BUTTON, r.x(), r.y(), r.w(), r.h());
            int ix = r.x() + 4, iy = r.y() + 4, iw = tileW - 8;
            Identifier thumb = Thumbnails.get(source, source.kind() == CaptureSource.Kind.CAMERA ? 30_000 : 3_000);
            if (thumb != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, thumb, ix, iy, 0, 0, iw, thumbH, iw, thumbH);
            } else {
                g.fill(ix, iy, ix + iw, iy + thumbH, PLACEHOLDER);
                String wait = font.plainSubstrByWidth(Component.translatable(source.portal() ? "ddbeam.dialog.portal"
                        : "ddbeam.dialog.preview").getString(), iw - 8);
                g.text(font, Component.literal(wait), ix + iw / 2 - font.width(wait) / 2, iy + thumbH / 2 - 4, SECONDARY, false);
            }
            int ly = iy + thumbH + 4;
            g.fill(ix, ly - 1, ix + iw, ly + font.lineHeight + 1, ROW_BG);
            String detail = source.kind() == CaptureSource.Kind.MONITOR && source.width() > 0 ? source.width() + "×" + source.height() : "";
            int detailW = detail.isEmpty() ? 0 : font.width(detail) + 6;
            Component name = source.kind() == CaptureSource.Kind.MONITOR && !source.portal()
                    ? Component.translatable("ddbeam.source.monitor.plain", source.index() + 1) : source.label();
            g.text(font, Component.literal(font.plainSubstrByWidth(name.getString(), iw - 4 - detailW)), ix + 2, ly, isSelected ? TEXT : 0xFFE0E0E0, true);
            if (!detail.isEmpty()) g.text(font, Component.literal(detail), ix + iw - 2 - font.width(detail), ly, SECONDARY, false);
            if (overGrid) tiles.add(new Tile(r, source));
        }
        g.disableScissor();
        if (maxScroll > 0) {
            int track = grid.h(), bar = Math.max(12, track * track / (track + (int) maxScroll));
            int barY = grid.y() + (int) ((track - bar) * (scroll / maxScroll));
            int sx = grid.x() + grid.w() + 2;
            g.fill(sx, grid.y(), sx + 3, grid.y() + track, SCROLL_TRACK);
            g.fill(sx, barY, sx + 3, barY + bar, SCROLL_THUMB);
        }
    }

    // ---- input ----

    private MouseButtonEvent virtual(MouseButtonEvent event) {
        return new MouseButtonEvent(event.x() * k, event.y() * k, event.buttonInfo());
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return super.mouseReleased(virtual(event));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return super.mouseDragged(virtual(event), dragX * k, dragY * k);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX * k, mouseY * k);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent realEvent, boolean doubleClick) {
        MouseButtonEvent event = virtual(realEvent);
        Select open = openSelect;
        if (open != null) {
            // an open list takes the click: a choice, or closing it
            for (int i = 0; i < open.labels.length; i++) {
                if (open.item(i).contains(event.x(), event.y())) {
                    open.pick(i);
                    minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1f));
                    return true;
                }
            }
            openSelect = null;
            if (open.button.isMouseOver(event.x(), event.y())) return true;
        }
        if (event.button() == 0) {
            for (Tile tile : List.copyOf(tiles)) {
                if (tile.rect().contains(event.x(), event.y())) {
                    select(tile.source());
                    minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1f));
                    if (doubleClick) goLive();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double realX, double realY, double scrollX, double scrollY) {
        double mouseX = realX * k, mouseY = realY * k;
        if (grid != null && grid.contains(mouseX, mouseY)) {
            scroll = Math.clamp(scroll - scrollY * 24, 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Enter: go live
        if (event.input() == 256 && openSelect != null) {
            openSelect = null;
            return true;
        }
        if ((event.input() == 257 || event.input() == 335) && selected != null) {
            goLive();
            return true;
        }
        return super.keyPressed(event);
    }

    private void goLive() {
        if (selected == null) return;
        BeamConfig config = BeamConfig.get();
        int[] res = RESOLUTIONS[resolution];
        config.maxWidth = res[0];
        config.maxHeight = res[1];
        config.fps = FRAME_RATES[frameRate];
        config.share = share;
        if (selected.kind() == CaptureSource.Kind.CAMERA) {
            if (!audio) config.cameraAudio = "none";
            else if ("none".equalsIgnoreCase(config.cameraAudio)) config.cameraAudio = "auto";
        } else if (selected.kind() != CaptureSource.Kind.TEST) {
            config.systemAudio = audio;
        }
        BeamConfig.save();
        Casts.start(target, new LocalCast.Settings(selected, res[0], res[1], config.fps,
                share && ClientPlayNetworking.canSend(BeamPayloads.Start.TYPE), audio));
        minecraft.setScreenAndShow(null);
    }
}
