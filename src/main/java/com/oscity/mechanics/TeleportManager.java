package com.oscity.mechanics;

import com.oscity.world.LocationRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class TeleportManager implements Listener {

    private final JavaPlugin plugin;
    private final LocationRegistry locations;
    private final boolean debugClicks;

    public TeleportManager(JavaPlugin plugin, LocationRegistry locations, boolean debugClicks) {
        this.plugin = plugin;
        this.locations = locations;
        this.debugClicks = debugClicks; //TO BE DELETED: set to true to see what you click, until you're done with locations
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("TeleportManager registered.");
    }

    private boolean sameBlock(Location a, Location b) {
        return a != null && b != null
                && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    @EventHandler
    public void onPress(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;

        Location clicked = e.getClickedBlock().getLocation();
        Material type = e.getClickedBlock().getType();

        // DEBUG TO BE DELETED: always tell you what you clicked (until you're done with locations)
        if (debugClicks) {
            e.getPlayer().sendMessage(Component.text(
                    "Clicked: " + type + " at " +
                            clicked.getBlockX() + " " + clicked.getBlockY() + " " + clicked.getBlockZ(),
                    NamedTextColor.YELLOW
            ));
        }

        if (!type.name().endsWith("_BUTTON")) return;

        Location tpPerChamber1 = locations.get("tpPerChamber1");
        Location permissionChamber = locations.get("permissionChamber");

        if (sameBlock(clicked, tpPerChamber1)) {
            e.getPlayer().teleport(permissionChamber);
            e.getPlayer().sendMessage(Component.text("Teleported to Permission Chamber", NamedTextColor.AQUA));
        }
    }
}
