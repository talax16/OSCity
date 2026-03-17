package com.oscity.mechanics;

import com.oscity.session.JourneyTracker;
import com.oscity.world.LocationRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;

public class TeleportManager implements Listener {

    private final JavaPlugin plugin;
    private final LocationRegistry locationRegistry;
    private final JourneyTracker journeyTracker;
    private final boolean debugClicks; //TO BE DELETED: set to true to see what you click, until you're done with locations

    // Map: button location → button data
    private final Map<Location, TeleportButton> buttons = new HashMap<>();

    private static class TeleportButton {
        String key;
        String destination;
        String message;

        TeleportButton(String key, String destination, String message) {
            this.key = key;
            this.destination = destination;
            this.message = message;
        }
    }

    public TeleportManager(JavaPlugin plugin, LocationRegistry locationRegistry, JourneyTracker journeyTracker, boolean debugClicks) {
        this.plugin = plugin;
        this.locationRegistry = locationRegistry;
        this.journeyTracker = journeyTracker;
        this.debugClicks = debugClicks;
        loadButtons();
    }
    
    /**
     * Load all teleport buttons from config
     */
    private void loadButtons() {
        buttons.clear();
        
        ConfigurationSection tpButtons = plugin.getConfig().getConfigurationSection("tpButtons");
        if (tpButtons == null) {
            plugin.getLogger().warning("No 'tpButtons' section in config.yml");
            return;
        }
        
        for (String key : tpButtons.getKeys(false)) {
            ConfigurationSection btn = tpButtons.getConfigurationSection(key);
            if (btn == null) continue;
            
            // Get button location
            String worldName = btn.getString("world");
            if (worldName == null) {
                plugin.getLogger().warning("Button '" + key + "' missing 'world'");
                continue;
            }
            
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                plugin.getLogger().warning("Button '" + key + "' refers to unknown world: " + worldName);
                continue;
            }
            
            int x = btn.getInt("x");
            int y = btn.getInt("y");
            int z = btn.getInt("z");
            Location buttonLoc = new Location(world, x, y, z);
            
            // Get destination and message
            String destination = btn.getString("destination");
            if (destination == null) {
                plugin.getLogger().warning("Button '" + key + "' missing 'destination'");
                continue;
            }
            
            String message = btn.getString("message", "&aTeleported!");
            
            // Store button data
            buttons.put(buttonLoc, new TeleportButton(key, destination, message));
        }
        
        plugin.getLogger().info("Loaded " + buttons.size() + " teleport buttons from config.yml");
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("TeleportManager registered.");
    }

    private boolean sameBlock(Location a, Location b) {
        return a != null && b != null
                && a.getWorld() != null && b.getWorld() != null
                && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    @EventHandler(ignoreCancelled = true)
    public void onPress(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;

        Location clicked = e.getClickedBlock().getLocation();
        Material type = e.getClickedBlock().getType();

        // DEBUG TO BE DELETED: always tell you what you clicked (until you're done with locations)
        if (debugClicks) {
            e.getPlayer().sendMessage(Component.text(
                    "Clicked: " + type + " at " +
                            clicked.getBlockX() + ", " + 
                            clicked.getBlockY() + ", " + 
                            clicked.getBlockZ(),
                    NamedTextColor.YELLOW
            ));
        }

        // Only handle buttons
        if (!type.name().endsWith("_BUTTON")) return;
        
        // Check if this button is registered
        for (Map.Entry<Location, TeleportButton> entry : buttons.entrySet()) {
            if (sameBlock(clicked, entry.getKey())) {
                TeleportButton button = entry.getValue();
                Player player = e.getPlayer();

                // Phase gate: block going to Calculator if already completed
                if ("tlbToCalculator".equals(button.key)) {
                    String phase = journeyTracker.getPhase(player);
                    if (!"tlb_spawn".equals(phase)) {
                        player.sendMessage("§cYou've already visited the Calculator Room. Make a hit or miss decision.");
                        return;
                    }
                }

                // Get destination location
                Location destination = locationRegistry.get(button.destination);
                if (destination == null) {
                    e.getPlayer().sendMessage(Component.text(
                        "§c[Error] Destination '" + button.destination + "' not found!", 
                        NamedTextColor.RED
                    ));
                    return;
                }
                
                // Teleport player
                e.getPlayer().teleport(destination);
                
                // Send message (with color code support)
                Component message = LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(button.message);
                e.getPlayer().sendMessage(message);
                
                return;
            }
        }
    }
}
