package io.github.tdual.screenshare.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: sharing and watching requests.
 * {@code target} is a player name for {@link #WATCH}; an empty string means "whoever is sharing".
 */
public record ControlPayload(int op, String target) implements CustomPacketPayload {
    public static final int STOP_SHARING = 1;
    public static final int WATCH = 2;
    public static final int UNWATCH = 3;

    public static final Type<ControlPayload> TYPE = new Type<>(Channels.CONTROL);
    public static final StreamCodec<FriendlyByteBuf, ControlPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeByte(p.op);
                Codecs.writeString(buf, p.target);
            },
            buf -> new ControlPayload(buf.readByte(), Codecs.readString(buf)));

    @Override
    public Type<ControlPayload> type() {
        return TYPE;
    }
}
