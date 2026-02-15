package com.oscity;

import com.oscity.mechanics.RoomDisplayManager;
import com.oscity.mechanics.TeleportManager;
import com.oscity.world.LocationRegistry;
import com.oscity.world.RoomRegistry;

import org.bukkit.plugin.java.JavaPlugin;

public class OSCity extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        RoomRegistry roomRegistry = new RoomRegistry(this);
        roomRegistry.loadFromConfig();

        boolean enabled = getConfig().getBoolean("roomDisplay.enabled", true);
        int interval = getConfig().getInt("roomDisplay.intervalTicks", 10);
        boolean clear = getConfig().getBoolean("roomDisplay.clearWhenOutside", true);

        if (enabled) {
            new RoomDisplayManager(this, roomRegistry, interval, clear).start();
        }

        boolean debugClicks = getConfig().getBoolean("debugClicks", true); //TO BE DELETED

        LocationRegistry locationRegistry = new LocationRegistry(this);
        locationRegistry.loadFromConfig();

        TeleportManager teleportManager = new TeleportManager(this, locationRegistry, debugClicks);
        teleportManager.register();

        getLogger().info("OSCity enabled!");
    }
}
