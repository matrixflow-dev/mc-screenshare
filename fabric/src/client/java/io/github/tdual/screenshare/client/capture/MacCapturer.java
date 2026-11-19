package io.github.tdual.screenshare.client.capture;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Uses the built-in {@code screencapture} command, and JavaScript for Automation to list monitors and windows.
 * macOS asks for Screen Recording permission the first time; without it only the wallpaper is captured
 * and window titles are hidden (the app name is still shown).
 */
final class MacCapturer implements ScreenCapturer {
    /** Prints {@code kind<TAB>id<TAB>name} lines. Window ids are the CGWindowIDs {@code screencapture -l} takes. */
    private static final String LIST_SCRIPT = """
            ObjC.import('AppKit');
            ObjC.import('CoreGraphics');
            var lines = [];
            var screens = $.NSScreen.screens.count;
            for (var i = 1; i <= screens; i++) lines.push('display\\t' + i + '\\t');
            // 1 = kCGWindowListOptionOnScreenOnly, 16 = kCGWindowListExcludeDesktopElements, 0 = kCGNullWindowID
            var windows = ObjC.deepUnwrap(ObjC.castRefToObject($.CGWindowListCopyWindowInfo(1 | 16, 0))) || [];
            windows.forEach(function (w) {
              var b = w.kCGWindowBounds || {};
              if (w.kCGWindowLayer !== 0 || b.Width < 50 || b.Height < 50) return;
              var name = (w.kCGWindowOwnerName || '') + (w.kCGWindowName ? ' - ' + w.kCGWindowName : '');
              lines.push('window\\t' + w.kCGWindowNumber + '\\t' + name.replace(/\\s/g, ' '));
            });
            lines.join('\\n');
            """;

    private final Path file;

    MacCapturer() {
        try {
            this.file = Files.createTempFile("screenshare", ".jpg");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<CaptureSource> list() throws IOException {
        byte[] out = Exec.run(List.of("osascript", "-l", "JavaScript", "-e", LIST_SCRIPT));
        return CaptureSource.parse(new String(out, StandardCharsets.UTF_8));
    }

    @Override
    public synchronized BufferedImage capture(CaptureSource source) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("screencapture", "-x", "-t", "jpg"));
        if (source.kind() == CaptureSource.Kind.WINDOW) {
            cmd.addAll(List.of("-o", "-l", source.id())); // -o: leave out the window shadow
        } else {
            cmd.addAll(List.of("-D", source.kind() == CaptureSource.Kind.DISPLAY ? source.id() : "1"));
        }
        cmd.add(file.toString());
        Process p = new ProcessBuilder(cmd)
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
