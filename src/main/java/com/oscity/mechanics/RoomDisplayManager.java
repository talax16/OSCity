package com.oscity.mechanics;

import com.oscity.world.RoomRegistry;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RoomDisplayManager {

    private final JavaPlugin plugin;
    private final RoomRegistry roomRegistry;
    private final int intervalTicks;
    private final boolean clearWhenOutside;

    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, String> lastRoom = new HashMap<>();

    public RoomDisplayManager(JavaPlugin plugin, RoomRegistry roomRegistry, int intervalTicks, boolean clearWhenOutside) {
        this.plugin = plugin;
        this.roomRegistry = roomRegistry;
        this.intervalTicks = intervalTicks;
        this.clearWhenOutside = clearWhenOutside;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID id = p.getUniqueId();
                String roomTitle = roomRegistry.getRoomTitleAt(p.getLocation());
                String prev = lastRoom.get(id);

                if (roomTitle == null) {
                    if (clearWhenOutside && prev != null) {
                        BossBar bar = bars.remove(id);
                        if (bar != null) p.hideBossBar(bar);
                        lastRoom.remove(id);
                    }
                    continue;
                }

                // Create bossbar if missing
                BossBar bar = bars.get(id);
                if (bar == null) {
                    bar = BossBar.bossBar(
                            Component.text(roomTitle, NamedTextColor.AQUA),
                            1.0f,
                            BossBar.Color.BLUE,
                            BossBar.Overlay.PROGRESS
                    );
                    bars.put(id, bar);
                    p.showBossBar(bar);
                }

                // Update only if changed (less flicker)
                if (!roomTitle.equals(prev)) {
                    bar.name(Component.text(roomTitle, NamedTextColor.AQUA));
                    lastRoom.put(id, roomTitle);
                }
            }
        }, 0L, intervalTicks);

        // Clean up when players leave
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
                UUID id = e.getPlayer().getUniqueId();
                BossBar bar = bars.remove(id);
                if (bar != null) e.getPlayer().hideBossBar(bar);
                lastRoom.remove(id);
            }
        }, plugin);
    }
}
