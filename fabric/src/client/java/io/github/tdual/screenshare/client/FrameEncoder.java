package io.github.tdual.screenshare.client;

import io.github.tdual.screenshare.net.FrameChunk;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Scales a screenshot, encodes it as JPEG and cuts it into payload-sized chunks. */
public final class FrameEncoder {
    /** A frame larger than this is dropped instead of sent. */
    public static final int MAX_FRAME_BYTES = 1_000_000;

    private FrameEncoder() {}

    public static BufferedImage scale(BufferedImage src, int maxWidth) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w > maxWidth) {
            h = Math.max(1, Math.round(h * (maxWidth / (float) w)));
            w = maxWidth;
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    public static byte[] jpeg(BufferedImage img, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    public static List<FrameChunk> split(int frameId, int width, int height, byte[] jpeg) {
        int count = (jpeg.length + FrameChunk.MAX_DATA - 1) / FrameChunk.MAX_DATA;
        List<FrameChunk> chunks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int from = i * FrameChunk.MAX_DATA;
            int to = Math.min(jpeg.length, from + FrameChunk.MAX_DATA);
            chunks.add(new FrameChunk(frameId, i, count, width, height, Arrays.copyOfRange(jpeg, from, to)));
        }
        return chunks;
    }
}
