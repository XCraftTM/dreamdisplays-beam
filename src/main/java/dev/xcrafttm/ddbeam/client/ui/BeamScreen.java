package dev.xcrafttm.ddbeam.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.xcrafttm.ddbeam.Texts;
import dev.xcrafttm.ddbeam.client.BeamClientNet;
import dev.xcrafttm.ddbeam.client.BeamConfig;
import dev.xcrafttm.ddbeam.client.Mc;
import dev.xcrafttm.ddbeam.client.capture.CameraModes;
import dev.xcrafttm.ddbeam.client.capture.CaptureSource;
import dev.xcrafttm.ddbeam.client.capture.Sources;
import dev.xcrafttm.ddbeam.client.cast.Casts;
import dev.xcrafttm.ddbeam.client.cast.LocalCast;
import dev.xcrafttm.ddbeam.client.cast.Targets;
import dev.xcrafttm.ddbeam.net.BeamPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
//#if MC >= 26.1
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#elif MC >= 1.20
import net.minecraft.client.gui.GuiGraphics;
//#else
import com.mojang.blaze3d.vertex.PoseStack;
//#endif
//#if MC >= 1.21.11
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//#endif

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * The cast dialog, laid out like a chat app's "go live" dialog (source tabs, preview tiles, stream quality, go
 * live) and drawn in Minecraft's own button and check box look inside Dream Displays' panels. Everything is drawn
 * through {@link Gfx}, so the dialog is the same on every Minecraft version; only the few Screen methods at the end
 * differ per version.
 */
public final class BeamScreen extends Screen {
    private enum Tab { SCREENS, WINDOWS, CAMERAS }

    private static final int[][] RESOLUTIONS = {{854, 480}, {1280, 720}, {1920, 1080}, {2560, 1440}};
    private static final String[] RESOLUTION_LABELS = {"480p", "720p", "1080p", "1440p"};
    private static final int[] FRAME_RATES = {15, 30, 60};

    // Dream Displays' look (its UiTheme)
    private static final int PAD = 10, GAP = 8, ROW = 20;
    private static final int TEXT = 0xFFFFFFFF, SECONDARY = 0xFFAAAAAA, ROW_BG = 0x40000000, CARD_BORDER = 0xFFE8E8E8, DISABLED = 0xFFA0A0A0;
    /** Vanilla's list and text field colours: black inside, grey border, white around the chosen row, yellow hover text. */
    private static final int LIST_BG = 0xF0000000, LIST_BORDER = 0xFFA0A0A0, LIST_CHOSEN = 0xFFFFFFFF, LIST_HOVER = 0xFFFFFFA0;
    private static final int PLACEHOLDER = 0xFF1C1C1C, SCROLL_TRACK = 0xFF202020, SCROLL_THUMB = 0xFF808080, LIVE = 0xFF55FF55;
    private static final int MIN_W = 560, MIN_H = 320, RES_SELECT_W = 80, FPS_SELECT_W = 74, MODE_SELECT_W = 190, ITEM_H = 16;

    private record Rect(int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** Something clickable this frame; a disabled one only shows its tooltip. */
    private record Hit(Rect rect, Runnable action, boolean enabled, List<Component> tooltip) {
    }

    /**
     * A select menu: a button showing the choice that opens the list of choices under (or over) it, drawn like
     * vanilla's selection lists (language, worlds): a dark box, one row per choice, the chosen one outlined.
     */
    private final class Select {
        final String[] labels;
        final IntSupplier get;
        final IntConsumer set;
        Rect button;
        /** The first row shown, when the list is longer than the room for it. */
        int first;

        Select(String[] labels, IntSupplier get, IntConsumer set) {
            this.labels = labels;
            this.get = get;
            this.set = set;
        }

        /** Under the button, else over it where there is more room; it scrolls when it is longer than that. */
        Rect list() {
            int below = (vh - 4 - button.y() - button.h() - 2) / ITEM_H, above = (button.y() - 6) / ITEM_H;
            boolean up = labels.length > below && above > below;
            int rows = Math.max(1, Math.min(labels.length, up ? above : below));
            int h = rows * ITEM_H + 2;
            return new Rect(button.x(), up ? button.y() - h - 1 : button.y() + button.h() + 1, button.w(), h);
        }

        int rows() {
            return (list().h() - 2) / ITEM_H;
        }

        boolean scrolls() {
            return labels.length > rows();
        }

        /** Row i, null while it is scrolled out of the list. */
        Rect item(int i) {
            Rect list = list();
            int row = i - first;
            if (row < 0 || row >= rows()) return null;
            return new Rect(list.x() + 1, list.y() + 1 + row * ITEM_H, list.w() - 2 - (scrolls() ? 4 : 0), ITEM_H);
        }

        boolean inList(double mx, double my) {
            return list().contains(mx, my);
        }

        void scroll(int rows) {
            first = Math.max(0, Math.min(labels.length - rows(), first + rows));
        }

        /** Opens it with the chosen row in view. */
        void open() {
            first = 0;
            scroll(get.getAsInt() - rows() / 2);
            openSelect = this;
        }
    }

    private final Screen parent;
    private final Targets.Target target;
    private final List<Hit> hits = new ArrayList<>();
    private final Select resolutionSelect, frameRateSelect;
    private Select openSelect;
    /** The camera mode select, made for one camera and its list of modes. */
    private Select modeSelect;
    private List<CameraModes.Mode> modeSelectModes;
    private CaptureSource modeSelectCamera;
    private Tab tab = Tab.SCREENS;
    private List<CaptureSource> monitors, windows, cameras;
    private CaptureSource selected;
    private int resolution, frameRate;
    private boolean share, audio;
    private double scroll, maxScroll;
    private long lastClickMs;
    private Object lastClickTile;
    /** Virtual size of the dialog and how many virtual pixels one GUI pixel is. */
    private float k = 1;
    private int vw, vh;
    private Rect sourcePanel, grid, qualityPanel;

    public BeamScreen(Screen parent, Targets.Target target) {
        super(Texts.tr("ddbeam.dialog.title"));
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
        String[] rates = new String[FRAME_RATES.length];
        for (int i = 0; i < rates.length; i++) rates[i] = Texts.tr("ddbeam.dialog.fps.value", FRAME_RATES[i]).getString();
        resolutionSelect = new Select(RESOLUTION_LABELS, () -> resolution, i -> resolution = i);
        frameRateSelect = new Select(rates, () -> frameRate, i -> frameRate = i);
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

    private static boolean serverRelays() {
        return BeamClientNet.canSend(BeamPayloads.START);
    }

    /** Shows the tab by its number (0 screens, 1 applications, 2 cameras). */
    public void showTab(int index) {
        tab = Tab.values()[Math.max(0, Math.min(Tab.values().length - 1, index))];
        scroll = 0;
    }

    /** Opens select menu i (0 resolution, 1 frame rate, 2 camera mode), or closes it with -1. */
    public void openSelect(int index) {
        Select select = index == 0 ? resolutionSelect : index == 1 ? frameRateSelect : index == 2 ? modeSelect : null;
        if (select != null) select.open();
        else openSelect = null;
    }

    @Override
    protected void init() {
        // like Dream Displays' menu: a finer GUI scale when the chosen one leaves too little room (whole scales only,
        // so the text stays sharp)
        int guiScale = (int) Math.round(minecraft.getWindow().getGuiScale());
        int fit = (int) Math.min(minecraft.getWindow().getWidth() / (double) MIN_W, minecraft.getWindow().getHeight() / (double) MIN_H);
        k = guiScale / (float) Math.max(1, Math.min(guiScale, fit));
        vw = Math.round(width * k);
        vh = Math.round(height * k);
        openSelect = null;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Mc.show(parent);
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
        }
    }

    // ---- drawing (virtual coordinates) ----

    private void draw(Gfx g, int realMouseX, int realMouseY) {
        int mouseX = (int) (realMouseX * k), mouseY = (int) (realMouseY * k);
        hits.clear();
        g.pushScale(k);
        boolean overList = openSelect != null && openSelect.inList(mouseX, mouseY);
        // under an open list nothing else is hovered
        int mx = overList ? -1 : mouseX, my = overList ? -1 : mouseY;

        boolean relays = serverRelays();
        Component resLabel = Texts.tr("ddbeam.dialog.resolution"), fpsLabel = Texts.tr("ddbeam.dialog.fps");
        Component shareLabel = Texts.tr("ddbeam.dialog.share");
        String audioKey = selected == null ? "ddbeam.dialog.audio" : switch (selected.kind()) {
            case CAMERA, TEST -> "ddbeam.dialog.audio.camera";
            case WINDOW -> "ddbeam.dialog.audio.window";
            default -> "ddbeam.dialog.audio.screen";
        };
        Component audioLabel = Texts.tr(audioKey);
        int box = Gfx.checkboxSize();
        int selectsW = g.width(resLabel) + 6 + RES_SELECT_W + 14 + g.width(fpsLabel) + 6 + FPS_SELECT_W;
        int boxesW = box + 4 + g.width(shareLabel) + 16 + box + 4 + g.width(audioLabel);
        boolean oneRow = selectsW + 20 + boxesW <= vw - 4 * PAD;
        int rows = oneRow ? 1 : 2;
        int footerY = vh - PAD - ROW;
        int qualityH = PAD + 9 + 6 + rows * ROW + (rows - 1) * 4 + PAD;
        qualityPanel = new Rect(PAD, footerY - GAP - qualityH, vw - 2 * PAD, qualityH);
        sourcePanel = new Rect(PAD, 20, vw - 2 * PAD, qualityPanel.y() - GAP - 20);

        // title and the display's size
        g.text(title, PAD, 6, TEXT, false);
        Component size = Texts.tr("ddbeam.dialog.display", target.width(), target.height());
        g.text(size, vw - PAD - g.width(size), 6, SECONDARY, false);

        // source: tabs and tiles
        g.panel(sourcePanel.x(), sourcePanel.y(), sourcePanel.w(), sourcePanel.h());
        g.text(Texts.tr("ddbeam.dialog.source"), sourcePanel.x() + PAD, sourcePanel.y() + PAD, TEXT, false);
        int tx = sourcePanel.x() + PAD, ty = sourcePanel.y() + PAD + 9 + 6;
        for (Tab t : Tab.values()) {
            if (t == Tab.WINDOWS && windows.isEmpty()) continue;
            Component label = Texts.tr("ddbeam.dialog.tab." + t.name().toLowerCase(Locale.ROOT));
            int w = Math.max(70, g.width(label) + 16);
            Tab picked = t;
            button(g, new Rect(tx, ty, w, ROW), label, tab == t, true, mx, my, () -> {
                tab = picked;
                scroll = 0;
            });
            tx += w + 4;
        }
        if (tab == Tab.CAMERAS && selected != null && selected.kind() == CaptureSource.Kind.CAMERA) cameraMode(g, tx, ty, mx, my);
        grid = new Rect(sourcePanel.x() + PAD, ty + ROW + 6, sourcePanel.w() - 2 * PAD - 6, sourcePanel.y() + sourcePanel.h() - PAD - (ty + ROW + 6));
        drawTiles(g, mx, my);

        // stream quality
        g.panel(qualityPanel.x(), qualityPanel.y(), qualityPanel.w(), qualityPanel.h());
        g.text(Texts.tr("ddbeam.dialog.quality"), qualityPanel.x() + PAD, qualityPanel.y() + PAD, TEXT, false);
        int qx = qualityPanel.x() + PAD, qy = qualityPanel.y() + PAD + 9 + 6;
        g.text(resLabel, qx, qy + 6, SECONDARY, false);
        int x = qx + g.width(resLabel) + 6;
        resolutionSelect.button = new Rect(x, qy, RES_SELECT_W, ROW);
        selectButton(g, resolutionSelect, mx, my);
        int fpsX = x + RES_SELECT_W + 14;
        g.text(fpsLabel, fpsX, qy + 6, SECONDARY, false);
        x = fpsX + g.width(fpsLabel) + 6;
        frameRateSelect.button = new Rect(x, qy, FPS_SELECT_W, ROW);
        selectButton(g, frameRateSelect, mx, my);

        // stream to players: only when the server has Beam (the mod or the plugin)
        int cx = oneRow ? x + FPS_SELECT_W + 20 : qx, cy = (oneRow ? qy : qy + ROW + 4) + (ROW - box) / 2;
        List<Component> shareTip = relays ? List.of(Texts.tr("ddbeam.dialog.share.tip"))
                : List.of(Texts.tr("ddbeam.dialog.share.off.title").withStyle(ChatFormatting.WHITE),
                Texts.tr("ddbeam.dialog.share.off.body").withStyle(ChatFormatting.GRAY));
        int afterShare = checkbox(g, cx, cy, shareLabel, share && relays, relays, mx, my, () -> share = !share, shareTip);
        String audioTipKey = selected == null ? "ddbeam.dialog.audio.tip" : switch (selected.kind()) {
            case CAMERA, TEST -> "ddbeam.dialog.audio.camera.tip";
            case WINDOW -> "ddbeam.dialog.audio.window.tip";
            default -> "ddbeam.dialog.audio.screen.tip";
        };
        checkbox(g, afterShare + 16, cy, audioLabel, audio, true, mx, my, () -> audio = !audio, List.of(Texts.tr(audioTipKey)));

        // footer: who sees it, cancel, stop, go live
        int bx = vw - PAD - 100;
        button(g, new Rect(bx, footerY, 100, ROW), Texts.tr(live() ? "ddbeam.dialog.switch" : "ddbeam.dialog.go"), false,
                selected != null, mx, my, this::goLive);
        if (live()) {
            bx -= 84;
            button(g, new Rect(bx, footerY, 80, ROW), Texts.tr("ddbeam.dialog.stop").withStyle(ChatFormatting.RED), false, true, mx, my, () -> {
                Casts.stop();
                onClose();
            });
        }
        bx -= 84;
        button(g, new Rect(bx, footerY, 80, ROW), Texts.tr("ddbeam.dialog.cancel"), false, true, mx, my, this::onClose);
        if (live()) {
            g.text(Texts.tr("ddbeam.dialog.live"), PAD, footerY + 6, LIVE, false);
        } else {
            Component who = Texts.tr(!relays ? "ddbeam.dialog.who.local" : share ? "ddbeam.dialog.who.everyone" : "ddbeam.dialog.who.you");
            int room = bx - 8 - PAD;
            if (room > 40) g.text(Texts.lit(g.font().plainSubstrByWidth(who.getString(), room)), PAD, footerY + 6, SECONDARY, false);
        }

        // an open select menu, then the tooltip, over everything
        if (openSelect != null) {
            g.top();
            Select open = openSelect;
            Rect list = open.list();
            g.fill(list.x(), list.y(), list.x() + list.w(), list.y() + list.h(), LIST_BG);
            g.outline(list.x(), list.y(), list.w(), list.h(), LIST_BORDER);
            for (int i = 0; i < open.labels.length; i++) {
                Rect r = open.item(i);
                if (r == null) continue;
                boolean hover = r.contains(mouseX, mouseY);
                if (i == open.get.getAsInt()) g.outline(r.x(), r.y(), r.w(), r.h(), LIST_CHOSEN);
                String text = g.font().plainSubstrByWidth(open.labels[i], r.w() - 12);
                g.text(Texts.lit(text), r.x() + 6, r.y() + (ITEM_H - 8) / 2, hover ? LIST_HOVER : TEXT, false);
            }
            if (open.scrolls()) {
                int track = list.h() - 2, shown = open.rows();
                int bar = Math.max(8, track * shown / open.labels.length);
                int barY = list.y() + 1 + (track - bar) * open.first / Math.max(1, open.labels.length - shown);
                int sx = list.x() + list.w() - 4;
                g.fill(sx, list.y() + 1, sx + 3, list.y() + 1 + track, SCROLL_TRACK);
                g.fill(sx, barY, sx + 3, barY + bar, SCROLL_THUMB);
            }
        } else {
            for (Hit hit : hits) {
                if (hit.tooltip() != null && hit.rect().contains(mouseX, mouseY)) {
                    g.tooltip(hit.tooltip(), mouseX, mouseY, vw, vh);
                    break;
                }
            }
        }
        g.popScale();
    }

    /** The selected camera's mode (size, frame rate, format) at the end of the tab row, where there is room. */
    private void cameraMode(Gfx g, int tabsEnd, int y, int mx, int my) {
        Component label = Texts.tr("ddbeam.dialog.camera_mode");
        Rect r = new Rect(sourcePanel.x() + sourcePanel.w() - PAD - MODE_SELECT_W, y, MODE_SELECT_W, ROW);
        int labelX = r.x() - 6 - g.width(label);
        if (labelX < tabsEnd + 8) return;
        g.text(label, labelX, y + 6, SECONDARY, false);
        CaptureSource camera = selected;
        List<CameraModes.Mode> modes = CameraModes.get(camera);
        if (modes == null) {
            button(g, r, Texts.tr("ddbeam.dialog.camera_mode.loading"), false, false, mx, my, null);
            return;
        }
        if (modeSelect == null || modeSelectModes != modes || modeSelectCamera != camera) {
            String[] labels = new String[modes.size() + 1];
            labels[0] = Texts.tr("ddbeam.dialog.camera_mode.auto").getString();
            for (int i = 0; i < modes.size(); i++) labels[i + 1] = modes.get(i).label().getString();
            modeSelect = new Select(labels, () -> {
                int i = modes.indexOf(CameraModes.chosen(camera));
                return i + 1;
            }, i -> CameraModes.choose(camera, i == 0 ? null : modes.get(i - 1)));
            modeSelectModes = modes;
            modeSelectCamera = camera;
        }
        modeSelect.button = r;
        selectButton(g, modeSelect, mx, my);
        hits.add(new Hit(r, null, false, List.of(Texts.tr("ddbeam.dialog.camera_mode.tip"))));
    }

    private void button(Gfx g, Rect r, Component label, boolean lit, boolean enabled, int mx, int my, Runnable action) {
        boolean hover = enabled && r.contains(mx, my);
        g.button(r.x(), r.y(), r.w(), r.h(), hover || lit, enabled);
        String text = g.font().plainSubstrByWidth(label.getString(), r.w() - 8);
        Component shown = text.equals(label.getString()) ? label : Texts.lit(text);
        g.text(shown, r.x() + r.w() / 2 - g.width(shown) / 2, r.y() + (r.h() - 8) / 2, enabled ? TEXT : DISABLED, true);
        hits.add(new Hit(r, action, enabled, null));
    }

    private void selectButton(Gfx g, Select select, int mx, int my) {
        Component label = Texts.lit(select.labels[select.get.getAsInt()] + "  ▼");
        Rect r = select.button;
        g.button(r.x(), r.y(), r.w(), r.h(), openSelect == select || r.contains(mx, my), true);
        g.text(label, r.x() + r.w() / 2 - g.width(label) / 2, r.y() + 6, TEXT, true);
        hits.add(new Hit(r, () -> {
            if (openSelect == select) openSelect = null;
            else select.open();
        }, true, null));
    }

    /** A check box with its label; returns where the label ends. */
    private int checkbox(Gfx g, int x, int y, Component label, boolean checked, boolean enabled, int mx, int my,
                         Runnable flip, List<Component> tooltip) {
        int box = Gfx.checkboxSize();
        Rect r = new Rect(x, y, box + 4 + g.width(label), box);
        boolean hover = enabled && r.contains(mx, my);
        g.checkbox(x, y, checked, hover);
        if (!enabled) g.fill(x, y, x + box, y + box, 0x90000000);
        // unavailable: greyed out and struck through (the tooltip says why)
        Component shown = enabled ? label : label.copy().withStyle(ChatFormatting.STRIKETHROUGH);
        g.text(shown, x + box + 4, y + (box - 8) / 2, enabled ? TEXT : DISABLED, enabled);
        hits.add(new Hit(r, flip, enabled, tooltip));
        return r.x() + r.w();
    }

    private void drawTiles(Gfx g, int mouseX, int mouseY) {
        List<CaptureSource> list = sources();
        if (list.isEmpty()) {
            Component empty = Texts.tr(tab == Tab.CAMERAS && cameras == null ? "ddbeam.dialog.searching"
                    : tab == Tab.CAMERAS ? "ddbeam.dialog.no_cameras" : "ddbeam.dialog.no_sources");
            g.text(empty, grid.x() + grid.w() / 2 - g.width(empty) / 2, grid.y() + grid.h() / 2 - 4, SECONDARY, false);
            return;
        }
        int cols = Math.max(1, Math.min(4, grid.w() / 150));
        int tileW = (grid.w() - GAP * (cols - 1)) / cols, thumbH = (tileW - 8) * 9 / 16, tileH = thumbH + 8 + 9 + 6;
        int rows = (list.size() + cols - 1) / cols;
        maxScroll = Math.max(0, rows * (tileH + GAP) - GAP - grid.h());
        scroll = Math.max(0, Math.min(maxScroll, scroll));
        boolean overGrid = grid.contains(mouseX, mouseY);
        g.scissor(grid.x() - 1, grid.y() - 1, grid.x() + grid.w() + 1, grid.y() + grid.h() + 1);
        for (int i = 0; i < list.size(); i++) {
            CaptureSource source = list.get(i);
            Rect r = new Rect(grid.x() + (i % cols) * (tileW + GAP), grid.y() + (i / cols) * (tileH + GAP) - (int) scroll, tileW, tileH);
            if (r.y() + tileH < grid.y() || r.y() > grid.y() + grid.h()) continue;
            boolean isSelected = selected != null && selected.key().equals(source.key());
            boolean hover = overGrid && r.contains(mouseX, mouseY);
            if (isSelected) g.outline(r.x() - 1, r.y() - 1, r.w() + 2, r.h() + 2, CARD_BORDER);
            g.button(r.x(), r.y(), r.w(), r.h(), hover || isSelected, true);
            int ix = r.x() + 4, iy = r.y() + 4, iw = tileW - 8;
            var thumb = Thumbnails.get(source, source.kind() == CaptureSource.Kind.CAMERA ? 30_000 : 3_000);
            if (thumb != null) {
                g.image(thumb, ix, iy, iw, thumbH);
            } else {
                g.fill(ix, iy, ix + iw, iy + thumbH, PLACEHOLDER);
                String wait = g.font().plainSubstrByWidth(Texts.tr(source.portal() ? "ddbeam.dialog.portal" : "ddbeam.dialog.preview").getString(), iw - 8);
                g.text(Texts.lit(wait), ix + iw / 2 - g.width(wait) / 2, iy + thumbH / 2 - 4, SECONDARY, false);
            }
            int ly = iy + thumbH + 4;
            g.fill(ix, ly - 1, ix + iw, ly + 10, ROW_BG);
            String detail = source.kind() == CaptureSource.Kind.MONITOR && source.width() > 0 ? source.width() + "×" + source.height() : "";
            int detailW = detail.isEmpty() ? 0 : g.width(detail) + 6;
            Component name = source.kind() == CaptureSource.Kind.MONITOR && !source.portal()
                    ? Texts.tr("ddbeam.source.monitor.plain", source.index() + 1) : source.label();
            g.text(Texts.lit(g.font().plainSubstrByWidth(name.getString(), iw - 4 - detailW)), ix + 2, ly, isSelected ? TEXT : 0xFFE0E0E0, true);
            if (!detail.isEmpty()) g.text(Texts.lit(detail), ix + iw - 2 - g.width(detail), ly, SECONDARY, false);
            if (overGrid) hits.add(new Hit(r, () -> onTile(source), true, null));
        }
        g.endScissor();
        if (maxScroll > 0) {
            int track = grid.h(), bar = Math.max(12, track * track / (track + (int) maxScroll));
            int barY = grid.y() + (int) ((track - bar) * (scroll / maxScroll));
            int sx = grid.x() + grid.w() + 2;
            g.fill(sx, grid.y(), sx + 3, grid.y() + track, SCROLL_TRACK);
            g.fill(sx, barY, sx + 3, barY + bar, SCROLL_THUMB);
        }
    }

    private void onTile(CaptureSource source) {
        long now = System.currentTimeMillis();
        boolean twice = source.key().equals(lastClickTile) && now - lastClickMs < 400;
        lastClickTile = source.key();
        lastClickMs = now;
        select(source);
        if (twice) goLive();
    }

    // ---- input (virtual coordinates) ----

    private boolean click(double x, double y) {
        Select open = openSelect;
        if (open != null) {
            // an open list takes the click: a choice, or closing it
            for (int i = 0; i < open.labels.length; i++) {
                Rect item = open.item(i);
                if (item != null && item.contains(x, y)) {
                    open.set.accept(i);
                    openSelect = null;
                    clickSound();
                    return true;
                }
            }
            // its border and scroll bar keep it open
            if (open.inList(x, y)) return true;
            openSelect = null;
            if (open.button.contains(x, y)) return true;
        }
        for (Hit hit : List.copyOf(hits)) {
            if (hit.enabled() && hit.action() != null && hit.rect().contains(x, y)) {
                clickSound();
                hit.action().run();
                return true;
            }
        }
        return false;
    }

    private boolean scrolled(double x, double y, double amount) {
        if (openSelect != null && openSelect.inList(x, y)) {
            openSelect.scroll(amount > 0 ? -1 : 1);
            return true;
        }
        if (grid != null && grid.contains(x, y)) {
            scroll = Math.max(0, Math.min(maxScroll, scroll - amount * 24));
            return true;
        }
        return false;
    }

    private boolean key(int key) {
        if (key == InputConstants.KEY_ESCAPE && openSelect != null) {
            openSelect = null;
            return true;
        }
        if ((key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) && selected != null) {
            goLive();
            return true;
        }
        return false;
    }

    private void clickSound() {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
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
        Casts.start(target, new LocalCast.Settings(selected, res[0], res[1], config.fps, share && serverRelays(), audio));
        Mc.show(null);
    }

    // ---- the Screen methods of each Minecraft version ----

    //#if MC >= 26.1
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        extractTransparentBackground(g);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        draw(new Gfx(g), mouseX, mouseY);
    }
    //#elif MC >= 1.20.2
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(g);
        draw(new Gfx(g), mouseX, mouseY);
    }
    //#elif MC >= 1.20
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        draw(new Gfx(g), mouseX, mouseY);
    }
    //#else
    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        renderBackground(pose);
        draw(new Gfx(pose), mouseX, mouseY);
    }
    //#endif

    //#if MC >= 1.21.11
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && click(event.x() * k, event.y() * k)) return true;
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return key(event.input()) || super.keyPressed(event);
    }
    //#else
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == InputConstants.MOUSE_BUTTON_LEFT && click(mouseX * k, mouseY * k)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        return key(key) || super.keyPressed(key, scanCode, modifiers);
    }
    //#endif

    //#if MC >= 1.20.2
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return scrolled(mouseX * k, mouseY * k, scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    //#else
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return scrolled(mouseX * k, mouseY * k, amount) || super.mouseScrolled(mouseX, mouseY, amount);
    }
    //#endif
}
