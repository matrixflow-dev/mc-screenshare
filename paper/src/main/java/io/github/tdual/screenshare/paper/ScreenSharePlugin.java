package io.github.tdual.screenshare.paper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;

/**
 * Relays screen frames between players running the Screen Share client mod.
 * The server never decodes images; it checks the chunk header and forwards the bytes.
 */
public final class ScreenSharePlugin extends JavaPlugin implements Listener {
    private static final long REJECT_NOTICE_INTERVAL_MILLIS = 10_000;

    private Relay relay;
    private final Map<UUID, Long> lastRejectNotice = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        relay = new Relay(
                getConfig().getInt("max-sharers", 3),
                getConfig().getInt("max-kilobytes-per-second", 600),
                getConfig().getInt("idle-timeout-seconds", 10));

        Messenger m = getServer().getMessenger();
        m.registerOutgoingPluginChannel(this, Wire.FRAME_DOWN);
        m.registerOutgoingPluginChannel(this, Wire.STATE);
        m.registerIncomingPluginChannel(this, Wire.FRAME_UP, (channel, player, msg) -> onChunk(player, msg));
        m.registerIncomingPluginChannel(this, Wire.CONTROL, (channel, player, msg) -> onControl(player, msg));
        getServer().getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Relay.Player ended : relay.expire(System.currentTimeMillis())) {
                broadcastState(Wire.STATE_STOPPED, ended.id(), ended.name());
            }
        }, 20L, 20L);
    }

    private void onChunk(Player player, byte[] msg) {
        if (!player.hasPermission("screenshare.share")) {
            return;
        }
        int len = Wire.validateChunk(msg);
        if (len < 0) {
            getLogger().warning("malformed frame chunk from " + player.getName());
            return;
        }
        Relay.Player sharer = new Relay.Player(player.getUniqueId(), player.getName());
        Relay.FrameResult result = relay.onChunk(sharer, msg.length, System.currentTimeMillis());
        switch (result) {
            case TOO_MANY_SHARERS -> {
                rejectOnce(player, "画面を共有できるのは同時に " + getConfig().getInt("max-sharers", 3) + " 人までです。");
                return;
            }
            case RATE_LIMITED -> {
                return;
            }
            case STARTED -> {
                getLogger().info(player.getName() + " started sharing");
                broadcastState(Wire.STATE_STARTED, sharer.id(), sharer.name());
            }
            case ACCEPTED -> {
            }
        }
        byte[] down = Wire.frameDown(sharer.id(), sharer.name(), msg);
        for (UUID id : relay.recipients(sharer, online())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.hasPermission("screenshare.watch")) {
                p.sendPluginMessage(this, Wire.FRAME_DOWN, down);
            }
        }
    }

    private void onControl(Player player, byte[] msg) {
        Wire.Control c = Wire.control(msg);
        if (c == null) {
            return;
        }
        switch (c.op()) {
            case Wire.OP_STOP_SHARING -> {
                String name = relay.stop(player.getUniqueId());
                if (name != null) {
                    getLogger().info(name + " stopped sharing");
                    broadcastState(Wire.STATE_STOPPED, player.getUniqueId(), name);
                }
            }
            case Wire.OP_WATCH -> {
                if (player.hasPermission("screenshare.watch")) {
                    relay.watch(player.getUniqueId(), c.target());
                }
            }
            case Wire.OP_UNWATCH -> relay.unwatch(player.getUniqueId());
            default -> {
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastRejectNotice.remove(id);
        String name = relay.leave(id);
        if (name != null) {
            broadcastState(Wire.STATE_STOPPED, id, name);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("stop")) {
            if (!sender.hasPermission("screenshare.admin")) {
                sender.sendMessage(Component.text("権限がありません。", NamedTextColor.RED));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            String name = target == null ? null : relay.stop(target.getUniqueId());
            if (name == null) {
                sender.sendMessage(Component.text(args[1] + " さんは画面を共有していません。"));
            } else {
                broadcastState(Wire.STATE_STOPPED, target.getUniqueId(), name);
                sender.sendMessage(Component.text(name + " さんの画面共有を止めました。"));
            }
            return true;
        }
        Map<UUID, String> sharers = relay.sharers();
        if (sharers.isEmpty()) {
            sender.sendMessage(Component.text("いま画面を共有している人はいません。"));
        } else {
            sender.sendMessage(Component.text("画面を共有中: " + String.join(", ", sharers.values())
                    + "（見ている人 " + relay.watcherCount() + " 人）"));
        }
        return true;
    }

    private void rejectOnce(Player player, String message) {
        long now = System.currentTimeMillis();
        Long last = lastRejectNotice.get(player.getUniqueId());
        if (last != null && now - last < REJECT_NOTICE_INTERVAL_MILLIS) {
            return;
        }
        lastRejectNotice.put(player.getUniqueId(), now);
        player.sendMessage(Component.text(message, NamedTextColor.RED));
        // Tell the client its own share is over so it stops capturing.
        player.sendPluginMessage(this, Wire.STATE, Wire.state(Wire.STATE_STOPPED, player.getUniqueId(), player.getName()));
    }

    private void broadcastState(int op, UUID sharer, String name) {
        byte[] msg = Wire.state(op, sharer, name);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendPluginMessage(this, Wire.STATE, msg);
        }
    }

    private static List<Relay.Player> online() {
        List<Relay.Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            out.add(new Relay.Player(p.getUniqueId(), p.getName()));
        }
        return out;
    }
}
