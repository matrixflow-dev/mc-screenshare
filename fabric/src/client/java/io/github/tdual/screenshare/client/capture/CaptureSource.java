package io.github.tdual.screenshare.client.capture;

import java.util.ArrayList;
import java.util.List;

/**
 * Something that can be shared: the whole desktop, one monitor or one window.
 * {@code id} is whatever the platform needs to capture it again (a monitor number, a geometry or a window handle);
 * {@code name} is a human-readable detail such as a resolution or a window title, and may be empty.
 */
public record CaptureSource(Kind kind, String id, String name) {
    public enum Kind { DESKTOP, DISPLAY, WINDOW }

    /** Parses the {@code kind<TAB>id<TAB>name} lines printed by the helper scripts. */
    static List<CaptureSource> parse(String text) {
        List<CaptureSource> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String[] parts = line.split("\t", 3);
            if (parts.length < 2) {
                continue;
            }
            Kind kind = switch (parts[0].trim()) {
                case "display" -> Kind.DISPLAY;
                case "window" -> Kind.WINDOW;
                default -> null;
            };
            if (kind != null) {
                out.add(new CaptureSource(kind, parts[1].trim(), parts.length > 2 ? parts[2].trim() : ""));
            }
        }
        return out;
    }
}
