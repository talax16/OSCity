package com.oscity;

import com.oscity.config.ConfigManager;
import com.oscity.core.RoomChangeListener;
import com.oscity.core.GuardianInteractionHandler;
import com.oscity.core.KernelGuardian;
import com.oscity.mechanics.RoomDisplayManager;
import com.oscity.mechanics.TeleportManager;
import com.oscity.persistence.SQLiteStudyDatabase;
import com.oscity.world.LocationRegistry;
import com.oscity.world.RoomRegistry;
import com.oscity.world.WorldManager;
import com.oscity.world.StructureManager;

import org.bukkit.plugin.java.JavaPlugin;

public class OSCity extends JavaPlugin {

    private ConfigManager configManager;
    private WorldManager worldManager;
    private RoomRegistry roomRegistry;
    private StructureManager structureManager;
    private LocationRegistry locationRegistry;
    private RoomDisplayManager roomDisplayManager;
    private TeleportManager teleportManager;
    private KernelGuardian kernelGuardian;
    private GuardianInteractionHandler guardianHandler;
    private RoomChangeListener roomChangeListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        // Check if Citizens is loaded
        if (getServer().getPluginManager().getPlugin("Citizens") == null) {
            getLogger().severe("Citizens not found! Kernel Guardian will not work.");
            getLogger().severe("Please install Citizens: https://ci.citizensnpcs.co/job/Citizens2/");
        }

        // Initialize user study database
        getLogger().info("Initializing user study database...");
        SQLiteStudyDatabase.initializeDatabase();
        SQLiteStudyDatabase.testConnection();
        getLogger().info("✓ User study database ready");

        // Initialize managers
        configManager = new ConfigManager(this);

        worldManager = new WorldManager(this);
        worldManager.initialize();

        roomRegistry = new RoomRegistry(this);
        roomRegistry.loadFromConfig();

        structureManager = new StructureManager(this, worldManager, roomRegistry);
        structureManager.initialize();

        locationRegistry = new LocationRegistry(this);
        locationRegistry.loadFromConfig();

        // Room display
        if (configManager.isRoomDisplayEnabled()) {
            int interval = configManager.getRoomDisplayInterval();
            boolean clear = getConfig().getBoolean("roomDisplay.clearWhenOutside", true);
            roomDisplayManager = new RoomDisplayManager(this, roomRegistry, interval, clear);
            roomDisplayManager.start();
        }

        // Teleport manager
        boolean debugClicks = configManager.isDebugMode();
        teleportManager = new TeleportManager(this, locationRegistry, debugClicks);
        teleportManager.register();

        
        // Not spawn guardian here, just initialize it
        kernelGuardian = new KernelGuardian(this);

        // Register handlers immediately
        guardianHandler = new GuardianInteractionHandler(this, configManager, kernelGuardian);
        getServer().getPluginManager().registerEvents(guardianHandler, this);
        
        roomChangeListener = new RoomChangeListener(this, kernelGuardian, roomRegistry, locationRegistry);
        getServer().getPluginManager().registerEvents(roomChangeListener, this);

        getLogger().info("OSCity enabled!");
    }

    @Override
    public void onDisable() {
        if (kernelGuardian != null) {
            kernelGuardian.destroy();
        }
        getLogger().info("OSCity disabled!");
    }

    // Getters
    public ConfigManager getConfigManager() {
        return configManager;
    }

    public RoomRegistry getRoomRegistry() {
        return roomRegistry;
    }

    public LocationRegistry getLocationRegistry() {
        return locationRegistry;
    }

    public KernelGuardian getKernelGuardian() {
        return kernelGuardian;
    }
}