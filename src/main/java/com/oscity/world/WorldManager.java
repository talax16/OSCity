package com.oscity.world;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Manages the OSCity game world.
 * Locks world properties to keep the study environment consistent across all sessions.
 */
public class WorldManager {

    private final JavaPlugin plugin;
    private World gameWorld;

    public WorldManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Called from OSCity.onEnable() after config is loaded.
     * Gets the world reference and applies study environment settings.
     */
    public void initialize() {
        String worldName = plugin.getConfig().getString("worldName", "OSCityWorld");
        gameWorld = Bukkit.getWorld(worldName);

        if (gameWorld == null) {
            plugin.getLogger().severe("World '" + worldName + "' not found! Is the world loaded?");
            return;
        }

        // Lock time to midday so lighting is consistent
        gameWorld.setTime(6000);
        gameWorld.setStorm(false);
        gameWorld.setThundering(false);
        gameWorld.setDifficulty(Difficulty.PEACEFUL);

        // Game rules for controlled study environment
        gameWorld.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        gameWorld.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        gameWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        gameWorld.setGameRule(GameRule.KEEP_INVENTORY, true);
        gameWorld.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        gameWorld.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        gameWorld.setGameRule(GameRule.FALL_DAMAGE, false);

        plugin.getLogger().info("World '" + worldName + "' initialized with study settings.");
    }

    public World getGameWorld() {
        return gameWorld;
    }
}
