package io.github.tdual.screenshare.client;

import io.github.tdual.screenshare.net.FrameChunk;
import java.io.ByteArrayOutputStream;

/**
 * Rebuilds one sharer's frames from chunks.
 * Only the newest frame is kept; when a chunk of a newer frame arrives, the partial older one is dropped.
 */
final class FrameAssembler {
    private int frameId = Integer.MIN_VALUE;
    private byte[][] parts;
    private int received;

    /** Returns the complete JPEG when the last missing chunk arrives, otherwise null. */
    byte[] accept(FrameChunk c) {
        if (c.count() <= 0 || c.index() >= c.count()) {
            return null;
        }
        if (c.frameId() == frameId && parts == null) {
            return null; // duplicate chunk of a frame we already completed
        }
        if (c.frameId() != frameId) {
            if (frameId != Integer.MIN_VALUE && c.frameId() - frameId < 0) {
                return null; // late chunk of an older frame
            }
            frameId = c.frameId();
            parts = new byte[c.count()][];
            received = 0;
        }
        if (parts.length != c.count() || parts[c.index()] != null) {
            return null;
        }
        parts[c.index()] = c.data();
        received++;
        if (received < parts.length) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        parts = null;
        return out.toByteArray();
    }
}
