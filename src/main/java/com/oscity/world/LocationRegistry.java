package com.oscity.world;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;

public class LocationRegistry {

    private final JavaPlugin plugin;
    private final Map<String, Location> locations = new HashMap<>();

    public LocationRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadFromConfig() {
        locations.clear();

        ConfigurationSection root = plugin.getConfig().getConfigurationSection("locations");
        if (root == null) {
            plugin.getLogger().warning("No 'locations' section found in config.yml");
            return;
        }

        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;

            String worldName = s.getString("world");
            if (worldName == null) {
                plugin.getLogger().warning("Location '" + key + "' missing 'world'");
                continue;
            }

            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                plugin.getLogger().warning("Location '" + key + "' refers to missing world: " + worldName);
                continue;
            }

            int x = s.getInt("x");
            int y = s.getInt("y");
            int z = s.getInt("z");

            locations.put(key, new Location(world, x, y, z));
        }

        plugin.getLogger().info("Loaded " + locations.size() + " locations from config.yml");
    }

    public Location get(String key) {
        Location loc = locations.get(key);
        if (loc == null) plugin.getLogger().warning("Unknown location key: " + key);
        return loc;
    }
}
