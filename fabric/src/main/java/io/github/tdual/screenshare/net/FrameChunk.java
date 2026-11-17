package io.github.tdual.screenshare.net;

import io.netty.buffer.ByteBuf;

/** One slice of a JPEG frame. A frame is split because client-to-server payloads are capped at 32767 bytes. */
public record FrameChunk(int frameId, int index, int count, int width, int height, byte[] data) {
    /** Largest JPEG slice we put in a single payload. */
    public static final int MAX_DATA = 30_000;

    void write(ByteBuf buf) {
        buf.writeInt(frameId);
        buf.writeShort(index);
        buf.writeShort(count);
        buf.writeShort(width);
        buf.writeShort(height);
        Codecs.writeBytes(buf, data);
    }

    static FrameChunk read(ByteBuf buf) {
        int frameId = buf.readInt();
        int index = buf.readUnsignedShort();
        int count = buf.readUnsignedShort();
        int width = buf.readUnsignedShort();
        int height = buf.readUnsignedShort();
        byte[] data = Codecs.readBytes(buf, MAX_DATA);
        return new FrameChunk(frameId, index, count, width, height, data);
    }
}
