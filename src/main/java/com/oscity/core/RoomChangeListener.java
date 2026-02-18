package com.oscity.core;

import com.oscity.world.LocationRegistry;
import com.oscity.world.RoomRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class RoomChangeListener implements Listener {

    private final JavaPlugin plugin;
    private final KernelGuardian guardian;
    private final RoomRegistry roomRegistry;
    private final LocationRegistry locationRegistry;
    
    private String currentRoom = null;
    private boolean guardianSpawned = false;

    public RoomChangeListener(JavaPlugin plugin, KernelGuardian guardian, 
                            RoomRegistry roomRegistry, LocationRegistry locationRegistry) {
        this.plugin = plugin;
        this.guardian = guardian;
        this.roomRegistry = roomRegistry;
        this.locationRegistry = locationRegistry;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Spawn guardian on first player join
            if (!guardianSpawned) {
                Location spawnLoc = locationRegistry.get("initialSpawn");
                if (spawnLoc != null) {
                    spawnLoc.add(3, 0, 0);
                    guardian.spawn(spawnLoc, "§6Kernel Guardian");
                    guardian.speak(
                        "You have been chosen to replace me, the Kernel Guardian. " +
                        "Proceed with caution. Trust your gut, and everything will be questioned."
                    );
                    guardianSpawned = true;
                    plugin.getLogger().info("Kernel Guardian spawned on first player join");
                } else {
                    plugin.getLogger().severe("Cannot spawn guardian - initialSpawn not found!");
                    return;
                }
                
                // Wait a moment for guardian to fully spawn
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    moveGuardianToPlayer(event.getPlayer());
                }, 20L);
                
            } else {
                // Guardian already exists, just move it
                moveGuardianToPlayer(event.getPlayer());
            }
        }, 20L);
    }

    /**
     * Helper method to move guardian to player's room
     */
    private void moveGuardianToPlayer(Player player) {
        Location loc = player.getLocation();
        RoomRegistry.Room room = roomRegistry.getRoomAt(loc);
        
        if (room != null) {
            currentRoom = room.title;
            
            if (guardian.isSpawned()) {
                if (room.npcPosition != null) {
                    plugin.getLogger().info("Moving guardian to " + room.title);
                    guardian.moveTo(room.npcPosition);
                } else {
                    Location nearPlayer = player.getLocation().clone().add(3, 0, 0);
                    guardian.moveTo(nearPlayer);
                }
            } else {
                plugin.getLogger().warning("Cannot move guardian - not spawned!");
            }
        } else {
            plugin.getLogger().info("Player not in any room");
            currentRoom = null;
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        // Only check when moving to a new block
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
            event.getFrom().getBlockY() == event.getTo().getBlockY() &&
            event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        if (!guardian.isSpawned()) return;

        RoomRegistry.Room newRoom = roomRegistry.getRoomAt(event.getTo());

        // Player entered a different room
        if (newRoom != null && !newRoom.title.equals(currentRoom)) {
            currentRoom = newRoom.title;
            
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (guardian.isSpawned()) {
                    if (newRoom.npcPosition != null) {
                        guardian.moveTo(newRoom.npcPosition);
                    } else {
                        Location nearPlayer = event.getPlayer().getLocation().clone().add(3, 0, 0);
                        guardian.moveTo(nearPlayer);
                    }
                }
            }, 5L);
        }
    }
}