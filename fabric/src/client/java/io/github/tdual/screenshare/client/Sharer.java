package io.github.tdual.screenshare.client;

import io.github.tdual.screenshare.client.capture.CaptureSource;
import io.github.tdual.screenshare.client.capture.ScreenCapturer;
import io.github.tdual.screenshare.net.ControlPayload;
import io.github.tdual.screenshare.net.FrameChunk;
import io.github.tdual.screenshare.net.FrameUpPayload;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/** Captures the chosen monitor or window on a background thread and sends each frame to the server. */
final class Sharer {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "screenshare-capture");
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> task;
    // Set on the client thread, read on the capture thread.
    private volatile ScreenCapturer capturer;
    private volatile CaptureSource source;
    private int frameId;
    private int failures;

    boolean isSharing() {
        return task != null;
    }

    /**
     * @param capturer closed when sharing stops
     * @param onError called on the client thread with a message for the player when sharing has to stop
     */
    void start(Config config, ScreenCapturer capturer, CaptureSource source, Consumer<String> onError) {
        if (task != null) {
            capturer.close();
            return;
        }
        failures = 0;
        this.capturer = capturer;
        this.source = source;
        long periodMs = Math.round(1000 / config.fps);
        task = executor.scheduleWithFixedDelay(() -> captureOnce(config, onError), 0, periodMs, TimeUnit.MILLISECONDS);
    }

    void stop(boolean tellServer) {
        if (task == null) {
            return;
        }
        task.cancel(false);
        task = null;
        ScreenCapturer done = capturer;
        capturer = null;
        executor.execute(done::close);
        if (tellServer && ClientPlayNetworking.canSend(ControlPayload.TYPE)) {
            ClientPlayNetworking.send(new ControlPayload(ControlPayload.STOP_SHARING, ""));
        }
    }

    private void captureOnce(Config config, Consumer<String> onError) {
        try {
            ScreenCapturer current = capturer;
            if (current == null) {
                return; // stopped
            }
            BufferedImage scaled = FrameEncoder.scale(current.capture(source), config.maxWidth);
            byte[] jpeg = FrameEncoder.jpeg(scaled, config.jpegQuality);
            if (jpeg.length > FrameEncoder.MAX_FRAME_BYTES) {
                ScreenShareClient.LOG.warn("frame of {} bytes dropped; lower max_width or jpeg_quality", jpeg.length);
                return;
            }
            List<FrameChunk> chunks = FrameEncoder.split(frameId++, scaled.getWidth(), scaled.getHeight(), jpeg);
            Minecraft.getInstance().execute(() -> {
                if (task == null || !ClientPlayNetworking.canSend(FrameUpPayload.TYPE)) {
                    return;
                }
                for (FrameChunk c : chunks) {
                    ClientPlayNetworking.send(new FrameUpPayload(c));
                }
            });
            failures = 0;
        } catch (Exception e) {
            ScreenShareClient.LOG.warn("screen capture failed", e);
            if (++failures >= 3) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                Minecraft.getInstance().execute(() -> {
                    stop(true);
                    onError.accept(msg);
                });
            }
        }
    }
}
