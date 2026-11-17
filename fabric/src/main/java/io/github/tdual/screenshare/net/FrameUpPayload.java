package io.github.tdual.screenshare.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: a slice of the sharer's current frame. */
public record FrameUpPayload(FrameChunk chunk) implements CustomPacketPayload {
    public static final Type<FrameUpPayload> TYPE = new Type<>(Channels.FRAME_UP);
    public static final StreamCodec<FriendlyByteBuf, FrameUpPayload> CODEC = StreamCodec.of(
            (buf, p) -> p.chunk.write(buf),
            buf -> new FrameUpPayload(FrameChunk.read(buf)));

    @Override
    public Type<FrameUpPayload> type() {
        return TYPE;
    }
}
