package com.oscity.core;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

public class KernelGuardian {

    private final JavaPlugin plugin;
    private NPC npc;
    private Location currentLocation;
    private BukkitTask lookAtPlayerTask;

    public KernelGuardian(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void spawn(Location location, String name) {
        if (npc != null) {
            npc.destroy();
        }

        // Create NPC
        npc = CitizensAPI.getNPCRegistry().createNPC(
            org.bukkit.entity.EntityType.PLAYER, 
            name
        );
        
        // Give book
        Equipment equipment = npc.getOrAddTrait(Equipment.class);
        equipment.set(Equipment.EquipmentSlot.HAND, new ItemStack(Material.BOOK));

        // Spawn
        npc.spawn(location);
        npc.setProtected(true);
        currentLocation = location.clone();
    
        // Then set skin
        setComputerSkin();
        
        startLookingAtPlayer();
        
        plugin.getLogger().info("Kernel Guardian spawned at " + 
            location.getBlockX() + ", " + 
            location.getBlockY() + ", " + 
            location.getBlockZ());
    }

    /**
    * Apply computer monitor skin to the NPC
    */
    private void setComputerSkin() {
        try {
            net.citizensnpcs.trait.SkinTrait skinTrait = npc.getOrAddTrait(net.citizensnpcs.trait.SkinTrait.class);
            
            String texture = "ewogICJ0aW1lc3RhbXAiIDogMTc3MTI3MjE5NTI0OCwKICAicHJvZmlsZUlkIiA6ICI0MjJlMzdiNjc4ZGU0OTA2YWFiMWU5NGY0N2E5NGM0OSIsCiAgInByb2ZpbGVOYW1lIiA6ICJzcGlmZnRvcGlhOSIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9mMGIwZWU2MTZkMDUyZDY1MDNkNmQxNWRjYjRjNTY4YTRiNmI0N2VhZmM4MWFmOTU4MWJlOGQwNjgyZjgxOTQzIgogICAgfQogIH0KfQ==";
            String signature = "mWYym8ccrulK9gQzmb6s/96iTFnpCvKE09fyuwizdFx46MgYEQtnRyHyALwiEUpevGOsLI0bBNf8zKYdQaxWjMfOtU9WfKD3z3o4Os8bWE6OEmE88VqhpCXX1oUCBhoC2q/osxK6Ez9kNrStsXeov8vF9mlskCzjpwoF1ImkJVVZmsC+MR9PEPcgfIdZTJ3Bjice4SACk5alCG1PadyahKL15M9c0IF08ciR9sZj7bGmWBXBtp8K8QeF+YXBDjYKH3ze0d9Ra4G3QL0q3mwP83SJxcFim9Tel6Zmxl5S2sSzGsw2WDKGqEB12TTJ8WKcD6oRt5IdxTPLRUwkaXc2LuJyJN4+q+w3LLTDhDc1qXlsq7QW74PvBbw8yUzpcWRPFa6CpU0c6GPxvz7QEV000ukDRxZI0PwcTV7jaJWvYAyswJTe3r3zc6+LEldV7nFR1f9Q7ZAKhc45UhgjpH5YrmhC3m519o27OmoJSOgoQp6cRc4q55HIUhnJj+n+vpv1wXlDa018GO6HUtiiB7WFTTU7BWfWMTxq+/4JiMfyW4T1TzULv5T3r6qmJlRYYZversTrINL3qLevtLma7XhXQjSaNnW4zG6Zam6Y2Urs1RWCzmV3YOYKoSXk6O1IX05QtqzzZF/L1+TOW5owsSYajgnrx0mBrAHeyz93qaP8z+g=";
            
            skinTrait.setSkinPersistent("computer_skin", signature, texture);
            
            plugin.getLogger().info("Computer skin applied successfully");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to apply computer skin: " + e.getMessage());
            e.printStackTrace();
        }
    }

    

    /**
     * Make NPC look at the player
     */
    private void startLookingAtPlayer() {
        if (lookAtPlayerTask != null) {
            lookAtPlayerTask.cancel();
        }

        lookAtPlayerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (npc == null || !npc.isSpawned()) return;

            Entity npcEntity = npc.getEntity();
            if (npcEntity == null) return;

            Player player = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            if (player == null) return;

            // Make NPC look at player
            Location npcLoc = npcEntity.getLocation();
            Location playerLoc = player.getEyeLocation();
            Vector direction = playerLoc.toVector().subtract(npcLoc.toVector());
            Location lookAt = npcLoc.setDirection(direction);
            npcEntity.teleport(lookAt);
            
        }, 0L, 10L); // Every 0.5 seconds
    }

    /**
     * Teleport NPC to a specific location
     */
    public void moveTo(Location location) {
        if (npc == null || !npc.isSpawned()) {
            plugin.getLogger().warning("Cannot move NPC - not spawned");
            return;
        }

        if (location == null) {
            plugin.getLogger().warning("Cannot move NPC - location is null");
            return;
        }

        npc.teleport(location, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        currentLocation = location.clone();
    }

    /**
     * Make the NPC say something to the player
     */
    public void speak(String message) {
        Player player = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (player != null) {
            player.sendMessage(Component.text("§6[Kernel Guardian] §f" + message));
        }
    }

    /**
     * Check if NPC is spawned
     */
    public boolean isSpawned() {
        return npc != null && npc.isSpawned();
    }

    /**
     * Get the NPC object
     */
    public NPC getNPC() {
        return npc;
    }

    /**
     * Get current location
     */
    public Location getLocation() {
        return currentLocation;
    }

    /**
     * Clean up and remove NPC
     */
    public void destroy() {
        if (lookAtPlayerTask != null) {
            lookAtPlayerTask.cancel();
            lookAtPlayerTask = null;
        }
        
        if (npc != null) {
            npc.destroy();
            npc = null;
        }
    }
}