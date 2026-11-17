package io.github.tdual.screenshare.net;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: someone started or stopped sharing. */
public record StatePayload(int op, UUID sharer, String sharerName) implements CustomPacketPayload {
    public static final int STOPPED = 0;
    public static final int STARTED = 1;

    public static final Type<StatePayload> TYPE = new Type<>(Channels.STATE);
    public static final StreamCodec<FriendlyByteBuf, StatePayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeByte(p.op);
                Codecs.writeUuid(buf, p.sharer);
                Codecs.writeString(buf, p.sharerName);
            },
            buf -> new StatePayload(buf.readByte(), Codecs.readUuid(buf), Codecs.readString(buf)));

    @Override
    public Type<StatePayload> type() {
        return TYPE;
    }
}
