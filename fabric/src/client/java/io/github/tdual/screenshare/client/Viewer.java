package io.github.tdual.screenshare.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.tdual.screenshare.net.FrameDownPayload;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Receives frames, decodes them off the render thread and keeps the newest one as a texture.
 * Only one sharer is shown at a time: the first one whose frame arrives, until that person stops.
 */
final class Viewer {
    static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath("screenshare", "view");

    private final ExecutorService decoder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "screenshare-decode");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean decoding = new AtomicBoolean();
    private final Map<UUID, FrameAssembler> assemblers = new HashMap<>();

    private UUID shownSharer;
    private String shownName = "";
    private DynamicTexture texture;
    private int width;
    private int height;
    private int selfTestFrames;

    /** Called on the client thread. */
    void onChunk(FrameDownPayload p) {
        if (shownSharer != null && !shownSharer.equals(p.sharer())) {
            return;
        }
        byte[] jpeg = assemblers.computeIfAbsent(p.sharer(), k -> new FrameAssembler()).accept(p.chunk());
        if (jpeg == null) {
            return;
        }
        if (shownSharer == null) {
            shownSharer = p.sharer();
            shownName = p.sharerName();
        }
        // Skip frames while the previous one is still being decoded; we only ever want the newest.
        if (!decoding.compareAndSet(false, true)) {
            return;
        }
        decoder.execute(() -> {
            try {
                NativeImage img = decode(jpeg);
                Minecraft.getInstance().execute(() -> upload(img));
            } catch (Exception e) {
                ScreenShareClient.LOG.warn("could not decode frame", e);
            } finally {
                decoding.set(false);
            }
        });
    }

    /** Called on the client thread when a sharer stops or leaves. */
    void onSharerStopped(UUID sharer) {
        assemblers.remove(sharer);
        if (sharer.equals(shownSharer)) {
            clear();
        }
    }

    void clear() {
        shownSharer = null;
        shownName = "";
        assemblers.clear();
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(TEXTURE_ID);
            texture = null;
        }
    }

    boolean hasFrame() {
        return texture != null;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    String shownName() {
        return shownName;
    }

    private void upload(NativeImage img) {
        if (shownSharer == null) {
            img.close(); // stopped while decoding
            return;
        }
        if (texture == null || img.getWidth() != width || img.getHeight() != height) {
            width = img.getWidth();
            height = img.getHeight();
            texture = new LinearTexture(img);
            Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, texture); // closes the old one
        } else {
            texture.setPixels(img); // closes the old pixels
            texture.upload();
        }
        if (Boolean.getBoolean("screenshare.selftest")) {
            ScreenShareClient.LOG.info("showing frame {}x{} from {}", width, height, shownName);
            if (++selfTestFrames == 5) {
                // Leave a few seconds to look at it, then save a screenshot of the window and quit.
                Minecraft mc = Minecraft.getInstance();
                new Thread(() -> {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {
                    }
                    mc.execute(() -> net.minecraft.client.Screenshot.grab(mc.gameDirectory, mc.gameRenderer.mainRenderTarget(),
                            msg -> ScreenShareClient.LOG.info("selftest screenshot: {}", msg.getString())));
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                    }
                    mc.execute(mc::stop);
                }).start();
            }
        }
    }

    private static NativeImage decode(byte[] jpeg) throws Exception {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(jpeg));
        if (src == null) {
            throw new IllegalArgumentException("not an image");
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        NativeImage img = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                img.setPixel(x, y, argb[row + x] | 0xFF000000);
            }
        }
        return img;
    }

    /** A dynamic texture sampled with linear filtering, so a scaled-down screen stays readable. */
    private static final class LinearTexture extends DynamicTexture {
        LinearTexture(NativeImage img) {
            super(() -> "screenshare view", img);
            this.sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
                    .getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.LINEAR);
        }
    }
}
