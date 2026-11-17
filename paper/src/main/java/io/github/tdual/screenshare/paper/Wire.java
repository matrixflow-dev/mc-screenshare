package io.github.tdual.screenshare.paper;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Byte layouts shared with the client mod (see the mod's {@code net} package).
 * Big-endian; strings are a 2-byte length followed by UTF-8.
 */
final class Wire {
    static final String FRAME_UP = "screenshare:frame_up";
    static final String FRAME_DOWN = "screenshare:frame_down";
    static final String CONTROL = "screenshare:control";
    static final String STATE = "screenshare:state";

    static final int MAX_CHUNK_DATA = 30_000;
    static final int MAX_CHUNKS = 64;

    static final int OP_STOP_SHARING = 1;
    static final int OP_WATCH = 2;
    static final int OP_UNWATCH = 3;

    static final int STATE_STOPPED = 0;
    static final int STATE_STARTED = 1;

    private Wire() {}

    /** Checks that a frame_up message is well formed. Returns the JPEG slice length, or -1 if invalid. */
    static int validateChunk(byte[] msg) {
        try {
            ByteBuffer b = ByteBuffer.wrap(msg);
            b.getInt(); // frame id
            int index = Short.toUnsignedInt(b.getShort());
            int count = Short.toUnsignedInt(b.getShort());
            int width = Short.toUnsignedInt(b.getShort());
            int height = Short.toUnsignedInt(b.getShort());
            int len = b.getInt();
            if (count == 0 || count > MAX_CHUNKS || index >= count) {
                return -1;
            }
            if (width == 0 || height == 0 || width > 4096 || height > 4096) {
                return -1;
            }
            if (len < 0 || len > MAX_CHUNK_DATA || len != b.remaining()) {
                return -1;
            }
            return len;
        } catch (BufferUnderflowException e) {
            return -1;
        }
    }

    /** frame_down = sharer UUID, sharer name, then the frame_up message unchanged. */
    static byte[] frameDown(UUID sharer, String name, byte[] chunk) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(chunk.length + 40);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writeUuid(out, sharer);
            writeString(out, name);
            out.write(chunk);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static byte[] state(int op, UUID sharer, String name) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(40);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(op);
            writeUuid(out, sharer);
            writeString(out, name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    record Control(int op, String target) {}

    /** Returns null if the message is malformed. */
    static Control control(byte[] msg) {
        try {
            ByteBuffer b = ByteBuffer.wrap(msg);
            int op = b.get();
            int len = Short.toUnsignedInt(b.getShort());
            if (len > 64 || len != b.remaining()) {
                return null;
            }
            byte[] s = new byte[len];
            b.get(s);
            return new Control(op, new String(s, StandardCharsets.UTF_8));
        } catch (BufferUnderflowException e) {
            return null;
        }
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(b.length);
        out.write(b);
    }
}
