package io.github.tdual.screenshare.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.tdual.screenshare.client.capture.CaptureSource;
import io.github.tdual.screenshare.client.capture.ScreenCapturer;
import io.github.tdual.screenshare.net.ControlPayload;
import io.github.tdual.screenshare.net.FrameDownPayload;
import io.github.tdual.screenshare.net.FrameUpPayload;
import io.github.tdual.screenshare.net.StatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScreenShareClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("screenshare");

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("screenshare", "main"));

    private final Sharer sharer = new Sharer();
    private final Viewer viewer = new Viewer();
    private final ViewOverlay overlay = new ViewOverlay(viewer, sharer);
    private Config config;
    private KeyMapping shareKey;
    private KeyMapping viewKey;

    @Override
    public void onInitializeClient() {
        config = Config.load();

        PayloadTypeRegistry.serverboundPlay().register(FrameUpPayload.TYPE, FrameUpPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ControlPayload.TYPE, ControlPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(FrameDownPayload.TYPE, FrameDownPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(FrameDownPayload.TYPE, (p, ctx) -> {
            if (overlay.mode() != ViewOverlay.Mode.OFF) {
                viewer.onChunk(p);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(StatePayload.TYPE, (p, ctx) -> onState(p));

        shareKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.screenshare.share", InputConstants.KEY_J, CATEGORY));
        viewKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.screenshare.view", InputConstants.KEY_K, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (shareKey.consumeClick()) {
                toggleSharing();
            }
            while (viewKey.consumeClick()) {
                cycleView();
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            sharer.stop(false);
            viewer.clear();
            overlay.setMode(ViewOverlay.Mode.OFF);
        }));

        // Development only: -Dscreenshare.selftest=true shares and watches your own screen on join.
        if (Boolean.getBoolean("screenshare.selftest")) {
            ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
                if (client.player != null && watch(client.player.getName().getString())) {
                    overlay.setMode(ViewOverlay.Mode.LARGE);
                    ScreenCapturer capturer = ScreenCapturer.forCurrentOs();
                    try {
                        CaptureSource source = capturer.list().getFirst();
                        startSharing(capturer, source, Component.literal(source.name()));
                    } catch (Exception e) {
                        LOG.warn("selftest could not start sharing", e);
                        capturer.close();
                    }
                }
            }));
        }

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("screenshare", "overlay"), overlay);
        registerCommands();
    }

    private void toggleSharing() {
        if (sharer.isSharing()) {
            sharer.stop(true);
            say(Component.translatable("screenshare.share.stopped"));
            return;
        }
        if (!ClientPlayNetworking.canSend(FrameUpPayload.TYPE)) {
            say(Component.translatable("screenshare.unsupported"));
            return;
        }
        Minecraft.getInstance().gui.setScreen(new SourcePickerScreen(config, this::startSharing));
    }

    private void startSharing(ScreenCapturer capturer, CaptureSource source, Component label) {
        if (!ClientPlayNetworking.canSend(FrameUpPayload.TYPE)) {
            capturer.close(); // left the server while picking
            return;
        }
        sharer.start(config, capturer, source, msg -> say(Component.translatable("screenshare.share.failed", msg)));
        say(Component.translatable("screenshare.share.started", label, shareKey.getTranslatedKeyMessage()));
    }

    /** Off, then small, then large, then off again. */
    private void cycleView() {
        ViewOverlay.Mode next = switch (overlay.mode()) {
            case OFF -> ViewOverlay.Mode.SMALL;
            case SMALL -> ViewOverlay.Mode.LARGE;
            case LARGE -> ViewOverlay.Mode.OFF;
        };
        if (next == ViewOverlay.Mode.SMALL) {
            watch("");
        } else if (next == ViewOverlay.Mode.OFF) {
            unwatch();
        }
        overlay.setMode(next);
    }

    private boolean watch(String target) {
        if (!ClientPlayNetworking.canSend(ControlPayload.TYPE)) {
            say(Component.translatable("screenshare.unsupported"));
            return false;
        }
        viewer.clear();
        ClientPlayNetworking.send(new ControlPayload(ControlPayload.WATCH, target));
        return true;
    }

    private void unwatch() {
        if (ClientPlayNetworking.canSend(ControlPayload.TYPE)) {
            ClientPlayNetworking.send(new ControlPayload(ControlPayload.UNWATCH, ""));
        }
        viewer.clear();
    }

    private void onState(StatePayload p) {
        Minecraft mc = Minecraft.getInstance();
        boolean self = mc.player != null && mc.player.getUUID().equals(p.sharer());
        if (p.op() == StatePayload.STARTED) {
            if (!self) {
                say(Component.translatable("screenshare.state.started", p.sharerName(), viewKey.getTranslatedKeyMessage()));
            }
        } else {
            viewer.onSharerStopped(p.sharer());
            if (self && sharer.isSharing()) {
                // The server ended our share (for example, a limit was hit).
                sharer.stop(false);
            }
            if (!self) {
                say(Component.translatable("screenshare.state.stopped", p.sharerName()));
            }
        }
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
                ClientCommands.literal("ss")
                        .then(ClientCommands.literal("watch")
                                .then(ClientCommands.argument("player", StringArgumentType.word())
                                        .executes(c -> {
                                            if (watch(StringArgumentType.getString(c, "player"))
                                                    && overlay.mode() == ViewOverlay.Mode.OFF) {
                                                overlay.setMode(ViewOverlay.Mode.SMALL);
                                            }
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("off").executes(c -> {
                            unwatch();
                            overlay.setMode(ViewOverlay.Mode.OFF);
                            return 1;
                        }))
                        .then(ClientCommands.literal("reload").executes(c -> {
                            config = Config.load();
                            c.getSource().sendFeedback(Component.translatable("screenshare.reloaded"));
                            return 1;
                        }))));
    }

    private static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(message);
        }
    }
}
