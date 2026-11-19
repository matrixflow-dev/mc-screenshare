package io.github.tdual.screenshare.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.tdual.screenshare.client.capture.CaptureSource;
import io.github.tdual.screenshare.client.capture.ScreenCapturer;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Lists the monitors and windows that can be shared, shows a live preview of the selected one,
 * and starts sharing only when the player confirms.
 * Listing and previews run on a background thread; the capturer it opens is handed to the sharer on confirm.
 */
final class SourcePickerScreen extends Screen {
    interface OnShare {
        /** Called on the client thread. The receiver owns {@code capturer} and must close it. */
        void share(ScreenCapturer capturer, CaptureSource source, Component label);
    }

    private static final Identifier PREVIEW_ID = Identifier.fromNamespaceAndPath("screenshare", "preview");
    private static final int PREVIEW_MAX_WIDTH = 640;
    private static final long PREVIEW_PERIOD_MS = 1000;
    private static final int PAD = 8;
    private static final int TOP = 24;
    private static final int BOTTOM = 36;

    private final Config config;
    private final OnShare onShare;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "screenshare-preview");
        t.setDaemon(true);
        return t;
    });
    /** Only touched on the executor thread. */
    private ScreenCapturer capturer;

    // Client thread only.
    private boolean started;
    private boolean closed;
    private List<CaptureSource> sources;
    private String listError;
    private SourceList list;
    private Button shareButton;
    private DynamicTexture preview;
    private CaptureSource previewShows;
    private int previewWidth;
    private int previewHeight;
    private String previewError;

    /** What the preview thread should capture; null when nothing is selected or the screen is closed. */
    private volatile CaptureSource selected;

    SourcePickerScreen(Config config, OnShare onShare) {
        super(Component.translatable("screenshare.picker.title"));
        this.config = config;
        this.onShare = onShare;
    }

    @Override
    protected void init() {
        int listW = Math.min(220, width * 2 / 5);
        list = new SourceList(minecraft, listW, height - TOP - BOTTOM, TOP);
        list.setX(PAD);
        addRenderableWidget(list);
        shareButton = addRenderableWidget(Button.builder(Component.translatable("screenshare.picker.share"), b -> share())
                .bounds(width / 2 - 154, height - 28, 150, 20).build());
        shareButton.active = selected != null;
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(width / 2 + 4, height - 28, 150, 20).build());
        if (sources != null) {
            list.fill(sources, selected); // rebuilt after a resize
        }

        if (!started) {
            started = true;
            executor.execute(this::loadSources);
            executor.scheduleWithFixedDelay(this::refreshPreview, PREVIEW_PERIOD_MS, PREVIEW_PERIOD_MS, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        closed = true;
        selected = null;
        executor.execute(() -> {
            if (capturer != null) {
                capturer.close();
                capturer = null;
            }
        });
        executor.shutdown();
        if (preview != null) {
            minecraft.getTextureManager().release(PREVIEW_ID);
            preview = null;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);

        if (sources == null || sources.isEmpty()) {
            Component msg = listError != null ? Component.translatable("screenshare.picker.failed", listError)
                    : sources == null ? Component.translatable("screenshare.picker.loading")
                    : Component.translatable("screenshare.picker.none");
            g.textWithWordWrap(font, msg, list.getX() + 4, TOP + 4, list.getWidth() - 8, 0xFFAAAAAA);
        }

        int x = list.getRight() + PAD;
        int y = TOP;
        int w = width - x - PAD;
        int h = height - TOP - BOTTOM;
        CaptureSource sel = selected;
        if (preview != null && sel != null && sel.equals(previewShows)) {
            int labelH = font.lineHeight + 4;
            int pw = w;
            int ph = Math.round(pw * previewHeight / (float) previewWidth);
            if (ph > h - labelH) {
                ph = h - labelH;
                pw = Math.round(ph * previewWidth / (float) previewHeight);
            }
            int px = x + (w - pw) / 2;
            g.fill(px - 1, y - 1, px + pw + 1, y + ph + 1, 0xFF000000);
            g.blit(RenderPipelines.GUI_TEXTURED, PREVIEW_ID, px, y, 0, 0, pw, ph,
                    previewWidth, previewHeight, previewWidth, previewHeight);
            g.centeredText(font, label(sel), x + w / 2, y + ph + 4, 0xFFFFFFFF);
            return;
        }
        Component msg = sel == null ? Component.translatable("screenshare.picker.select")
                : previewError != null ? Component.translatable("screenshare.picker.preview_failed", previewError)
                : Component.translatable("screenshare.picker.preview_loading");
        g.textWithWordWrap(font, msg, x + 4, y + h / 2 - font.lineHeight, w - 8, 0xFFAAAAAA);
    }

    /** "Screen 2 (DP-1 2560x1440)", "Entire desktop" or the window title. */
    Component label(CaptureSource source) {
        return switch (source.kind()) {
            case DESKTOP -> Component.translatable("screenshare.picker.desktop");
            case DISPLAY -> {
                String n = Integer.toString(displayNumber(source));
                yield Component.translatable("screenshare.picker.display", source.name().isEmpty() ? n : n + " (" + source.name() + ")");
            }
            case WINDOW -> Component.literal(source.name());
        };
    }

    private int displayNumber(CaptureSource source) {
        int n = 0;
        for (CaptureSource s : sources) {
            if (s.kind() == CaptureSource.Kind.DISPLAY) {
                n++;
                if (s.equals(source)) {
                    return n;
                }
            }
        }
        return 1;
    }

    private void select(CaptureSource source) {
        if (source == null ? selected == null : source.equals(selected)) {
            return;
        }
        selected = source;
        previewError = null;
        shareButton.active = source != null;
        if (source != null && !closed) {
            executor.execute(this::refreshPreview);
        }
    }

    private void share() {
        CaptureSource source = selected;
        if (source == null || closed) {
            return;
        }
        if (source.kind() == CaptureSource.Kind.DISPLAY) {
            config.display = displayNumber(source);
            config.save();
        }
        Component label = label(source);
        // Queued before removed() closes it, so the capturer is handed over instead.
        executor.execute(() -> {
            ScreenCapturer c = capturer;
            capturer = null;
            if (c != null) {
                minecraft.execute(() -> onShare.share(c, source, label));
            }
        });
        onClose();
    }

    // Executor thread.
    private void loadSources() {
        try {
            capturer = ScreenCapturer.forCurrentOs();
            List<CaptureSource> found = capturer.list();
            minecraft.execute(() -> {
                if (closed) {
                    return;
                }
                sources = found;
                // Preselect the monitor from the config.
                CaptureSource initial = found.isEmpty() ? null : found.getFirst();
                int n = 0;
                for (CaptureSource s : found) {
                    if (s.kind() == CaptureSource.Kind.DISPLAY && ++n == config.display) {
                        initial = s;
                    }
                }
                list.fill(found, initial);
            });
        } catch (Exception e) {
            ScreenShareClient.LOG.warn("could not list screens and windows", e);
            String msg = message(e);
            minecraft.execute(() -> listError = msg);
        }
    }

    // Executor thread.
    private void refreshPreview() {
        CaptureSource source = selected;
        if (capturer == null || source == null) {
            return;
        }
        try {
            BufferedImage img = FrameEncoder.scale(capturer.capture(source), PREVIEW_MAX_WIDTH);
            NativeImage pixels = Viewer.toNativeImage(img);
            minecraft.execute(() -> showPreview(source, pixels));
        } catch (Exception e) {
            ScreenShareClient.LOG.debug("preview of {} failed", source, e);
            String msg = message(e);
            minecraft.execute(() -> {
                if (source.equals(selected)) {
                    previewError = msg;
                }
            });
        }
    }

    private void showPreview(CaptureSource source, NativeImage img) {
        if (closed || !source.equals(selected)) {
            img.close();
            return;
        }
        if (preview == null || img.getWidth() != previewWidth || img.getHeight() != previewHeight) {
            previewWidth = img.getWidth();
            previewHeight = img.getHeight();
            preview = new Viewer.LinearTexture(img);
            minecraft.getTextureManager().register(PREVIEW_ID, preview); // closes the old one
        } else {
            preview.setPixels(img); // closes the old pixels
            preview.upload();
        }
        previewShows = source;
        previewError = null;
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private final class SourceList extends ObjectSelectionList<SourceEntry> {
        SourceList(Minecraft mc, int width, int height, int y) {
            super(mc, width, height, y, mc.font.lineHeight + 7);
            centerListVertically = false;
        }

        void fill(List<CaptureSource> sources, CaptureSource select) {
            clearEntries();
            for (CaptureSource s : sources) {
                SourceEntry e = new SourceEntry(s, label(s));
                addEntry(e);
                if (s.equals(select)) {
                    setSelected(e);
                }
            }
        }

        @Override
        public int getRowWidth() {
            return getWidth() - 14; // room for the scroll bar
        }

        @Override
        public void setSelected(SourceEntry entry) {
            super.setSelected(entry);
            select(entry == null ? null : entry.source);
        }
    }

    private final class SourceEntry extends ObjectSelectionList.Entry<SourceEntry> {
        final CaptureSource source;
        final Component label;

        SourceEntry(CaptureSource source, Component label) {
            this.source = source;
            this.label = label;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float delta) {
            String text = font.plainSubstrByWidth(label.getString(), getContentWidth());
            int color = source.kind() == CaptureSource.Kind.WINDOW ? 0xFFFFFFFF : 0xFFFFFF80;
            g.text(font, text, getContentX(), getContentYMiddle() - font.lineHeight / 2, color, false);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (doubleClick) {
                list.setSelected(this);
                share();
            }
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.translatable("narrator.select", label);
        }
    }
}
