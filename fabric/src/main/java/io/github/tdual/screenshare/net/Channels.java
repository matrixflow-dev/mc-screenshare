package io.github.tdual.screenshare.net;

import net.minecraft.resources.Identifier;

/** Plugin channel names. The Paper plugin uses the same strings. */
public final class Channels {
    public static final String NAMESPACE = "screenshare";
    public static final Identifier FRAME_UP = Identifier.fromNamespaceAndPath(NAMESPACE, "frame_up");
    public static final Identifier FRAME_DOWN = Identifier.fromNamespaceAndPath(NAMESPACE, "frame_down");
    public static final Identifier CONTROL = Identifier.fromNamespaceAndPath(NAMESPACE, "control");
    public static final Identifier STATE = Identifier.fromNamespaceAndPath(NAMESPACE, "state");

    private Channels() {}
}
