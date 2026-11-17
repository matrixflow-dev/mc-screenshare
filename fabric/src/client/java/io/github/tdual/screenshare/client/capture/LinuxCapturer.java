package io.github.tdual.screenshare.client.capture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Tries {@code grim} (Wayland) and then ImageMagick's {@code import} (X11). */
final class LinuxCapturer implements ScreenCapturer {
    private static final List<List<String>> COMMANDS = List.of(
            List.of("grim", "-t", "jpeg", "-"),
            List.of("import", "-window", "root", "jpg:-"));

    @Override
    public BufferedImage capture() throws IOException {
        IOException last = null;
        for (List<String> cmd : COMMANDS) {
            try {
                Process p = new ProcessBuilder(cmd).start();
                byte[] bytes = p.getInputStream().readAllBytes();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
                    if (img != null) {
                        return img;
                    }
                }
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
        throw new IOException("install grim or ImageMagick to share your screen", last);
    }
}
