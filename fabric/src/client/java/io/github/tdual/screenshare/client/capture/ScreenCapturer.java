package io.github.tdual.screenshare.client.capture;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

/**
 * Lists and grabs the user's monitors and windows.
 * Minecraft runs with {@code java.awt.headless=true}, so {@code java.awt.Robot} is unavailable;
 * each platform shells out to its own screenshot tool instead.
 * Calls may block for a few seconds, so make them off the client thread.
 */
public interface ScreenCapturer extends AutoCloseable {
    /** What can be shared right now, monitors first. */
    List<CaptureSource> list() throws IOException;

    BufferedImage capture(CaptureSource source) throws IOException;

    @Override
    default void close() {}

    static ScreenCapturer forCurrentOs() {
        if (Boolean.getBoolean("screenshare.testpattern")) {
            return new TestPatternCapturer();
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return new MacCapturer();
        }
        if (os.contains("win")) {
            return new WindowsCapturer();
        }
        return new LinuxCapturer();
    }
}
