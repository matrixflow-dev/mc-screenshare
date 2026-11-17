package io.github.tdual.screenshare.net;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: a slice of some sharer's frame, relayed to a watcher. */
public record FrameDownPayload(UUID sharer, String sharerName, FrameChunk chunk) implements CustomPacketPayload {
    public static final Type<FrameDownPayload> TYPE = new Type<>(Channels.FRAME_DOWN);
    public static final StreamCodec<FriendlyByteBuf, FrameDownPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                Codecs.writeUuid(buf, p.sharer);
                Codecs.writeString(buf, p.sharerName);
                p.chunk.write(buf);
            },
            buf -> new FrameDownPayload(Codecs.readUuid(buf), Codecs.readString(buf), FrameChunk.read(buf)));

    @Override
    public Type<FrameDownPayload> type() {
        return TYPE;
    }
}
