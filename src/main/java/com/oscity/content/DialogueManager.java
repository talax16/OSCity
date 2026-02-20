package com.oscity.content;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Loads dialogue.yml and delivers Kernel Guardian lines to players.
 *
 * All dialogue is prefixed with §6[Kernel Guardian] §f to match
 * the format used in KernelGuardian.speak().
 *
 * Placeholder syntax: {key} in YAML lines is replaced at runtime
 * from the player's JourneyTracker vars map.
 */
public class DialogueManager {

    private static final String PREFIX = "§6[Kernel Guardian] §f";

    private final JavaPlugin plugin;
    private FileConfiguration dialogue;

    public DialogueManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "dialogue.yml");
        if (!file.exists()) {
            plugin.saveResource("dialogue.yml", false);
        }
        dialogue = YamlConfiguration.loadConfiguration(file);
        plugin.getLogger().info("DialogueManager: loaded dialogue.yml");
    }

    /**
     * Send dialogue at the given YAML path to the player.
     * Example path: "rooms.tlb_room.at_spawn"
     *
     * Placeholders in the lines are replaced using the provided vars map.
     * Logs a warning if the path is not found.
     */
    private static final long LINE_DELAY_TICKS = 40L; // 2 seconds between lines

    public void speak(Player player, String path, Map<String, String> vars) {
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
     * Send a single literal line (not from YAML) through the guardian prefix.
     */
    public void speakLine(Player player, String line, Map<String, String> vars) {
        player.sendMessage(PREFIX + replacePlaceholders(line, vars));
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

    // ── Internal ─────────────────────────────────────────────────────────────

    private String replacePlaceholders(String line, Map<String, String> vars) {
        if (vars == null || vars.isEmpty()) return line;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            line = line.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return line;
    }
}
