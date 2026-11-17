package io.github.tdual.screenshare.paper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who is sharing and who is watching whom. Has no Bukkit dependency so it can be unit tested.
 * Not thread safe; the plugin only calls it from the main server thread.
 */
final class Relay {
    record Player(UUID id, String name) {}

    enum FrameResult { ACCEPTED, STARTED, RATE_LIMITED, TOO_MANY_SHARERS }

    private static final class Share {
        final String name;
        long lastFrameMillis;
        double allowanceBytes;

        Share(String name, long now, double capacity) {
            this.name = name;
            this.lastFrameMillis = now;
            this.allowanceBytes = capacity;
        }
    }

    private final int maxSharers;
    private final double bytesPerMilli;
    private final double burstBytes;
    private final long idleTimeoutMillis;
    private final Map<UUID, Share> shares = new HashMap<>();
    /** Watcher id to target name; an empty target means "anyone except me". */
    private final Map<UUID, String> watchers = new HashMap<>();

    Relay(int maxSharers, int maxKilobytesPerSecond, int idleTimeoutSeconds) {
        this.maxSharers = maxSharers;
        this.bytesPerMilli = maxKilobytesPerSecond * 1024 / 1000.0;
        this.burstBytes = maxKilobytesPerSecond * 1024 * 2.0;
        this.idleTimeoutMillis = idleTimeoutSeconds * 1000L;
    }

    FrameResult onChunk(Player sharer, int bytes, long now) {
        Share s = shares.get(sharer.id());
        boolean started = false;
        if (s == null) {
            if (shares.size() >= maxSharers) {
                return FrameResult.TOO_MANY_SHARERS;
            }
            s = new Share(sharer.name(), now, burstBytes);
            shares.put(sharer.id(), s);
            started = true;
        }
        s.allowanceBytes = Math.min(burstBytes, s.allowanceBytes + (now - s.lastFrameMillis) * bytesPerMilli);
        s.lastFrameMillis = now;
        if (s.allowanceBytes < bytes) {
            return started ? FrameResult.STARTED : FrameResult.RATE_LIMITED;
        }
        s.allowanceBytes -= bytes;
        return started ? FrameResult.STARTED : FrameResult.ACCEPTED;
    }

    /** Players among {@code online} who should receive frames from {@code sharer}. */
    List<UUID> recipients(Player sharer, Iterable<Player> online) {
        List<UUID> out = new ArrayList<>();
        for (Player p : online) {
            String target = watchers.get(p.id());
            if (target == null) {
                continue;
            }
            boolean wantsAnyone = target.isEmpty() && !p.id().equals(sharer.id());
            if (wantsAnyone || target.equalsIgnoreCase(sharer.name())) {
                out.add(p.id());
            }
        }
        return out;
    }

    void watch(UUID watcher, String target) {
        watchers.put(watcher, target);
    }

    void unwatch(UUID watcher) {
        watchers.remove(watcher);
    }

    /** Returns the name of the share that ended, or null if the player was not sharing. */
    String stop(UUID sharer) {
        Share s = shares.remove(sharer);
        return s == null ? null : s.name;
    }

    /** Forget a player who left. Returns their share's name if they were sharing. */
    String leave(UUID player) {
        watchers.remove(player);
        return stop(player);
    }

    /** Ends shares that have gone quiet and returns them. */
    List<Player> expire(long now) {
        List<Player> ended = new ArrayList<>();
        Iterator<Map.Entry<UUID, Share>> it = shares.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Share> e = it.next();
            if (now - e.getValue().lastFrameMillis > idleTimeoutMillis) {
                ended.add(new Player(e.getKey(), e.getValue().name));
                it.remove();
            }
        }
        return ended;
    }

    Map<UUID, String> sharers() {
        Map<UUID, String> out = new HashMap<>();
        shares.forEach((id, s) -> out.put(id, s.name));
        return out;
    }

    int watcherCount() {
        return watchers.size();
    }
}
