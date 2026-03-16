package com.oscity.content;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Loads dialogue.yml and delivers Kernel Guardian lines to players.
 * Freezes player movement for the duration of each dialogue sequence.
 */
public class DialogueManager implements Listener {

    private static final String PREFIX = "§6[Kernel Guardian] §f";
    private static final long LINE_DELAY_TICKS = 40L; // 2 seconds between lines

    private final JavaPlugin plugin;
    private FileConfiguration dialogue;

    // ── Freeze state ──────────────────────────────────────────────────────────

    private final Set<UUID>              frozenPlayers   = new HashSet<>();
    private final Map<UUID, Location>    frozenLocations = new HashMap<>();
    private final Map<UUID, Integer>     unfreezeTaskIds = new HashMap<>();

    public DialogueManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveResource("dialogue.yml", true);
        File file = new File(plugin.getDataFolder(), "dialogue.yml");
        dialogue = YamlConfiguration.loadConfiguration(file);
        plugin.getLogger().info("DialogueManager: loaded dialogue.yml");
    }

    // ── Speak ─────────────────────────────────────────────────────────────────

    /**
     * Send dialogue at the given YAML path to the player.
     * Freezes the player for the duration of the dialogue.
     */
    public void speak(Player player, String path, Map<String, String> vars) {
        plugin.getLogger().info("[DialogueManager] Speaking: " + path);
        List<String> lines = dialogue.getStringList(path);
        plugin.getLogger().info("[DialogueManager] Found " + lines.size() + " lines for: " + path);
        if (lines.isEmpty()) {
            plugin.getLogger().warning("DialogueManager: no content at '" + path + "'");
            return;
        }

        freeze(player);

        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i);
            Bukkit.getScheduler().runTaskLater(plugin,
                () -> player.sendMessage(PREFIX + replacePlaceholders(line, vars)),
                i * LINE_DELAY_TICKS);
        }

        // Cancel any existing unfreeze task and reschedule to end of this dialogue
        Integer existing = unfreezeTaskIds.remove(player.getUniqueId());
        if (existing != null) Bukkit.getScheduler().cancelTask(existing);

        long unfreezeDelay = (lines.size() - 1) * LINE_DELAY_TICKS + LINE_DELAY_TICKS;
        int taskId = Bukkit.getScheduler().runTaskLater(plugin,
            () -> unfreeze(player), unfreezeDelay).getTaskId();
        unfreezeTaskIds.put(player.getUniqueId(), taskId);
    }

    /**
     * Send a single literal line (not from YAML) through the guardian prefix.
     * Does not freeze — used for short one-off messages.
     */
    public void speakLine(Player player, String line, Map<String, String> vars) {
        player.sendMessage(PREFIX + replacePlaceholders(line, vars));
    }

    /**
     * Send dialogue at the given YAML path with delays between lines, but no freeze.
     * Used for terminal rooms where the player should still be able to move.
     */
    public void speakDelayed(Player player, String path, Map<String, String> vars) {
        List<String> lines = dialogue.getStringList(path);
        if (lines.isEmpty()) {
            plugin.getLogger().warning("DialogueManager: no content at '" + path + "'");
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i);
            Bukkit.getScheduler().runTaskLater(plugin,
                () -> player.sendMessage(PREFIX + replacePlaceholders(line, vars)),
                i * LINE_DELAY_TICKS);
        }
    }

    /**
     * Send all lines at a given YAML path instantly (no freeze, no delay).
     * Used for short meta-responses like guidance on/off confirmations.
     */
    public void speakInstant(Player player, String path, Map<String, String> vars) {
        List<String> lines = dialogue.getStringList(path);
        if (lines.isEmpty()) {
            plugin.getLogger().warning("DialogueManager: no content at '" + path + "'");
            return;
        }
        for (String line : lines) {
            player.sendMessage(PREFIX + replacePlaceholders(line, vars));
        }
    }

    /**
     * Retrieve a single string from YAML (for non-list entries like explanations).
     */
    public String getString(String path, Map<String, String> vars) {
        String value = dialogue.getString(path, null);
        if (value == null) return null;
        return replacePlaceholders(value, vars);
    }

    public boolean hasPath(String path) {
        return dialogue != null && dialogue.contains(path);
    }

    // ── Freeze / unfreeze ────────────────────────────────────────────────────

    /** Immediately unfreeze a player (call when entering a quiz or on forced phase change). */
    public void unfreeze(Player player) {
        UUID uuid = player.getUniqueId();
        frozenPlayers.remove(uuid);
        frozenLocations.remove(uuid);
        Integer task = unfreezeTaskIds.remove(uuid);
        if (task != null) Bukkit.getScheduler().cancelTask(task);
    }

    public boolean isFrozen(Player player) {
        return frozenPlayers.contains(player.getUniqueId());
    }

    private void freeze(Player player) {
        UUID uuid = player.getUniqueId();
        frozenPlayers.add(uuid);
        frozenLocations.put(uuid, player.getLocation().clone());
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!frozenPlayers.contains(player.getUniqueId())) return;

        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;

        // Allow head rotation, block position changes
        if (to.getX() != from.getX() || to.getY() != from.getY() || to.getZ() != from.getZ()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        unfreeze(event.getPlayer());
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private String replacePlaceholders(String line, Map<String, String> vars) {
        if (vars == null || vars.isEmpty()) return line;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            line = line.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return line;
    }
}
