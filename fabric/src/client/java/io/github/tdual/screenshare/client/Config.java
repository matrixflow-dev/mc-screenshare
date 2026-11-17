package io.github.tdual.screenshare.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** Settings stored in {@code config/screenshare.properties}. */
public final class Config {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("screenshare.properties");

    /** Frames per second sent while sharing. Each frame is sent to every watcher, so keep this low. */
    public double fps = 1.0;
    /** Frames are scaled down to at most this width before encoding. */
    public int maxWidth = 1280;
    /** JPEG quality, 0.0 to 1.0. */
    public float jpegQuality = 0.6f;
    /** Which monitor to share, starting at 1. */
    public int display = 1;

    public static Config load() {
        Config c = new Config();
        Properties p = new Properties();
        if (Files.exists(FILE)) {
            try (Reader r = Files.newBufferedReader(FILE)) {
                p.load(r);
            } catch (IOException e) {
                ScreenShareClient.LOG.warn("could not read {}", FILE, e);
            }
        }
        c.fps = clamp(parse(p, "fps", c.fps), 0.2, 5.0);
        c.maxWidth = (int) clamp(parse(p, "max_width", c.maxWidth), 320, 1920);
        c.jpegQuality = (float) clamp(parse(p, "jpeg_quality", c.jpegQuality), 0.1, 1.0);
        c.display = (int) clamp(parse(p, "display", c.display), 1, 16);
        c.save();
        return c;
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("fps", Double.toString(fps));
        p.setProperty("max_width", Integer.toString(maxWidth));
        p.setProperty("jpeg_quality", Float.toString(jpegQuality));
        p.setProperty("display", Integer.toString(display));
        try (Writer w = Files.newBufferedWriter(FILE)) {
            p.store(w, "Screen Share settings. fps: 0.2-5, max_width: 320-1920, jpeg_quality: 0.1-1.0, display: monitor number");
        } catch (IOException e) {
            ScreenShareClient.LOG.warn("could not write {}", FILE, e);
        }
    }

    private static double parse(Properties p, String key, double fallback) {
        try {
            return Double.parseDouble(p.getProperty(key, Double.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
