package io.github.tdual.screenshare.client.capture;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Uses the built-in {@code screencapture} command.
 * macOS asks for Screen Recording permission the first time; without it only the wallpaper is captured.
 */
final class MacCapturer implements ScreenCapturer {
    private final int display;
    private final Path file;

    MacCapturer(int display) {
        this.display = display;
        try {
            this.file = Files.createTempFile("screenshare", ".jpg");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public BufferedImage capture() throws IOException {
        Process p = new ProcessBuilder("screencapture", "-x", "-t", "jpg", "-D", Integer.toString(display), file.toString())
                .redirectErrorStream(true)
                .start();
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("screencapture timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        if (p.exitValue() != 0) {
            throw new IOException("screencapture failed: " + new String(p.getInputStream().readAllBytes()).trim());
        }
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) {
            throw new IOException("screencapture produced no image");
        }
        return img;
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }
}
