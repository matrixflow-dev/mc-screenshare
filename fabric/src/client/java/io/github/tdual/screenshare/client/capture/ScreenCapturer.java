package io.github.tdual.screenshare.client.capture;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Grabs the user's desktop.
 * Minecraft runs with {@code java.awt.headless=true}, so {@code java.awt.Robot} is unavailable;
 * each platform shells out to its own screenshot tool instead.
 */
public interface ScreenCapturer extends AutoCloseable {
    BufferedImage capture() throws IOException;

    @Override
    default void close() {}

    static ScreenCapturer forCurrentOs(int display) {
        if (Boolean.getBoolean("screenshare.testpattern")) {
            return new TestPatternCapturer();
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return new MacCapturer(display);
        }
        if (os.contains("win")) {
            return new WindowsCapturer(display);
        }
        return new LinuxCapturer();
    }
}
