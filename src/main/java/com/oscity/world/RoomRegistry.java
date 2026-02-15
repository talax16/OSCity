package com.oscity.world;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class RoomRegistry {

    public static class Room {
        public final String key;
        public final String title;
        public final World world;
        public final int minX, minY, minZ, maxX, maxY, maxZ;

        public Room(String key, String title, World world,
                    int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.key = key;
            this.title = title;
            this.world = world;
            this.minX = Math.min(minX, maxX);
            this.minY = Math.min(minY, maxY);
            this.minZ = Math.min(minZ, maxZ);
            this.maxX = Math.max(minX, maxX);
            this.maxY = Math.max(minY, maxY);
            this.maxZ = Math.max(minZ, maxZ);
        }

        public boolean contains(org.bukkit.Location loc) {
            if (loc.getWorld() == null || !loc.getWorld().equals(world)) return false;
            int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
            return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
        }
    }

    private final JavaPlugin plugin;
    private final List<Room> rooms = new ArrayList<>();

    public RoomRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadFromConfig() {
        rooms.clear();

        ConfigurationSection root = plugin.getConfig().getConfigurationSection("rooms");
        if (root == null) {
            plugin.getLogger().warning("No 'rooms' section found in config.yml");
            return;
        }

        for (String key : root.getKeys(false)) {
            ConfigurationSection r = root.getConfigurationSection(key);
            if (r == null) continue;

            String title = r.getString("title", key);
            String worldName = r.getString("world");
            if (worldName == null) {
                plugin.getLogger().warning("Room '" + key + "' missing 'world'");
                continue;
            }

            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                plugin.getLogger().warning("Room '" + key + "' refers to missing world: " + worldName);
                continue;
            }

            ConfigurationSection min = r.getConfigurationSection("min");
            ConfigurationSection max = r.getConfigurationSection("max");
            if (min == null || max == null) {
                plugin.getLogger().warning("Room '" + key + "' missing min/max bounds");
                continue;
            }

            rooms.add(new Room(
                    key, title, world,
                    min.getInt("x"), min.getInt("y"), min.getInt("z"),
                    max.getInt("x"), max.getInt("y"), max.getInt("z")
            ));
        }

        plugin.getLogger().info("Loaded " + rooms.size() + " rooms from config.yml");
    }

    public String getRoomTitleAt(org.bukkit.Location loc) {
        for (Room room : rooms) {
            if (room.contains(loc)) return room.title;
        }
        return null;
    }
}
