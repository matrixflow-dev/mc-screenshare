package io.github.tdual.screenshare.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/** Draws the shared screen in the top-right corner, and a "sharing" marker while we share. */
final class ViewOverlay implements HudElement {
    enum Mode { OFF, SMALL, LARGE }

    private static final int MARGIN = 4;
    private final Viewer viewer;
    private final Sharer sharer;
    private Mode mode = Mode.OFF;

    ViewOverlay(Viewer viewer, Sharer sharer) {
        this.viewer = viewer;
        this.sharer = sharer;
    }

    Mode mode() {
        return mode;
    }

    void setMode(Mode mode) {
        this.mode = mode;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mode != Mode.OFF) {
            drawView(g, mc);
        }
        // Drawn last so the large view never hides it.
        if (sharer.isSharing()) {
            g.text(mc.font, Component.translatable("screenshare.hud.sharing"), MARGIN, MARGIN, 0xFFFF5555, true);
        }
    }

    private void drawView(GuiGraphicsExtractor g, Minecraft mc) {
        int screenW = g.guiWidth();
        int screenH = g.guiHeight();
        int labelH = mc.font.lineHeight + 2;
        if (!viewer.hasFrame()) {
            Component waiting = Component.translatable("screenshare.hud.waiting");
            g.text(mc.font, waiting, screenW - mc.font.width(waiting) - MARGIN, MARGIN, 0xFFFFFFFF, true);
            return;
        }
        float fraction = mode == Mode.SMALL ? 0.35f : 0.9f;
        int w = Math.round(screenW * fraction);
        int h = Math.round(w * viewer.height() / (float) viewer.width());
        int maxH = screenH - labelH - MARGIN * 3;
        if (h > maxH) {
            h = maxH;
            w = Math.round(h * viewer.width() / (float) viewer.height());
        }
        int x = mode == Mode.SMALL ? screenW - w - MARGIN : (screenW - w) / 2;
        int y = MARGIN;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        g.blit(RenderPipelines.GUI_TEXTURED, Viewer.TEXTURE_ID, x, y, 0, 0, w, h,
                viewer.width(), viewer.height(), viewer.width(), viewer.height());
        Component label = Component.translatable("screenshare.hud.label", viewer.shownName());
        g.text(mc.font, label, x, y + h + 2, 0xFFFFFFFF, true);
    }
}
