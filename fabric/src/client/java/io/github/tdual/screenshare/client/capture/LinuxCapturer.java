package io.github.tdual.screenshare.client.capture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * The whole desktop comes from {@code grim} (Wayland) or ImageMagick's {@code import} (X11).
 * On X11, monitors are found with {@code xrandr} and windows with {@code wmctrl}; Wayland only offers the whole desktop,
 * because compositors do not let other programs look at single windows.
 */
final class LinuxCapturer implements ScreenCapturer {
    private static final List<List<String>> DESKTOP_COMMANDS = List.of(
            List.of("grim", "-t", "jpeg", "-"),
            List.of("import", "-window", "root", "jpg:-"));
    /** One line of {@code xrandr --listmonitors}, e.g. {@code  0: +*DP-1 2560/597x1440/336+0+0  DP-1}. */
    private static final Pattern MONITOR = Pattern.compile("(\\d+)/\\d+x(\\d+)/\\d+\\+(-?\\d+)\\+(-?\\d+)\\s+(\\S+)\\s*$");

    @Override
    public List<CaptureSource> list() {
        List<CaptureSource> out = new ArrayList<>();
        out.add(new CaptureSource(CaptureSource.Kind.DESKTOP, "", ""));
        if (System.getenv("WAYLAND_DISPLAY") != null || System.getenv("DISPLAY") == null) {
            return out;
        }
        try {
            for (String line : text("xrandr", "--listmonitors")) {
                Matcher m = MONITOR.matcher(line);
                if (m.find()) {
                    String size = m.group(1) + "x" + m.group(2);
                    out.add(new CaptureSource(CaptureSource.Kind.DISPLAY,
                            size + "+" + m.group(3) + "+" + m.group(4), m.group(5) + " " + size));
                }
            }
        } catch (IOException ignored) {
            // No xrandr: the whole desktop is still offered.
        }
        try {
            for (String line : text("wmctrl", "-l")) {
                // 0x03a00003  0 host Title; desktop -1 marks panels and docks.
                String[] parts = line.trim().split("\\s+", 4);
                if (parts.length == 4 && !parts[1].equals("-1")) {
                    out.add(new CaptureSource(CaptureSource.Kind.WINDOW, parts[0], parts[3]));
                }
            }
        } catch (IOException ignored) {
            // No wmctrl: only monitors are offered.
        }
        return out;
    }

    @Override
    public BufferedImage capture(CaptureSource source) throws IOException {
        return switch (source.kind()) {
            case DESKTOP -> captureDesktop();
            case DISPLAY -> read(Exec.run(List.of("import", "-window", "root", "-crop", source.id(), "+repage", "jpg:-")));
            case WINDOW -> read(Exec.run(List.of("import", "-window", source.id(), "jpg:-")));
        };
    }

    private static BufferedImage captureDesktop() throws IOException {
        IOException last = null;
        for (List<String> cmd : DESKTOP_COMMANDS) {
            try {
                return read(Exec.run(cmd));
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("install grim or ImageMagick to share your screen", last);
    }

    private static BufferedImage read(byte[] bytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) {
            throw new IOException("no image");
        }
        return img;
    }

    private static List<String> text(String... cmd) throws IOException {
        return new String(Exec.run(List.of(cmd)), StandardCharsets.UTF_8).lines().toList();
    }
}
