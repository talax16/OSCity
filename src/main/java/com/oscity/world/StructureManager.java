package com.oscity.world;

import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Holds references to all major structures in OSCityWorld.
 * Structures are pre-built manually in the world — this class provides
 * a central access point for structure-level operations (e.g. resetting
 * interactive elements, querying which structure a location belongs to).
 */
public class StructureManager {

    private final JavaPlugin plugin;
    private final WorldManager worldManager;
    private final RoomRegistry roomRegistry;

    public StructureManager(JavaPlugin plugin, WorldManager worldManager, RoomRegistry roomRegistry) {
        this.plugin = plugin;
        this.worldManager = worldManager;
        this.roomRegistry = roomRegistry;
    }

    /**
     * Called from OSCity.onEnable() after WorldManager.initialize().
     * Use this to register any structure-specific listeners or scheduled tasks.
     */
    public void initialize() {
        World world = worldManager.getGameWorld();
        if (world == null) {
            plugin.getLogger().severe("StructureManager: game world is not loaded, skipping init.");
            return;
        }
        plugin.getLogger().info("StructureManager initialized. Structures ready in world: " + world.getName());
    }

    /**
     * Returns the name of the structure (room title) at the given location,
     * or null if the location is not inside any registered room.
     */
    public String getStructureAt(org.bukkit.Location loc) {
        return roomRegistry.getRoomTitleAt(loc);
    }

    public WorldManager getWorldManager() {
        return worldManager;
    }

    public RoomRegistry getRoomRegistry() {
        return roomRegistry;
    }
}
