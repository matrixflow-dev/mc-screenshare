package io.github.tdual.screenshare.net;

import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Wire primitives shared with the Paper plugin.
 * Everything is big-endian; strings are a 2-byte length followed by UTF-8.
 */
final class Codecs {
    private Codecs() {}

    static void writeString(ByteBuf buf, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(b.length);
        buf.writeBytes(b);
    }

    static String readString(ByteBuf buf) {
        int len = buf.readUnsignedShort();
        byte[] b = new byte[len];
        buf.readBytes(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    static void writeUuid(ByteBuf buf, UUID id) {
        buf.writeLong(id.getMostSignificantBits());
        buf.writeLong(id.getLeastSignificantBits());
    }

    static UUID readUuid(ByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }

    static void writeBytes(ByteBuf buf, byte[] data) {
        buf.writeInt(data.length);
        buf.writeBytes(data);
    }

    static byte[] readBytes(ByteBuf buf, int max) {
        int len = buf.readInt();
        if (len < 0 || len > max) {
            throw new IllegalArgumentException("chunk too large: " + len);
        }
        byte[] b = new byte[len];
        buf.readBytes(b);
        return b;
    }
}
