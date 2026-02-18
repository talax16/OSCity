package com.oscity.core;

import com.oscity.config.ConfigManager;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public class GuardianInteractionHandler implements Listener {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final KernelGuardian guardian;

    public GuardianInteractionHandler(JavaPlugin plugin, ConfigManager config, KernelGuardian guardian) {
        this.plugin = plugin;
        this.config = config;
        this.guardian = guardian;
    }

    @EventHandler
    public void onNPCRightClick(NPCRightClickEvent event) {
        // Check if the clicked NPC is our Kernel Guardian
        if (event.getNPC().equals(guardian.getNPC())) {
            Player player = event.getClicker();
            
            // Show menu based on player's current step/room
            showMainMenu(player);
        }
    }

    /**
     * Show the main interaction menu
     */
    private void showMainMenu(Player player) {
        player.sendMessage(Component.text("════════════════════════════════", NamedTextColor.GOLD));
        player.sendMessage(Component.text("Kernel Guardian", NamedTextColor.AQUA, net.kyori.adventure.text.format.TextDecoration.BOLD));
        player.sendMessage(Component.text(""));
        player.sendMessage(Component.text("What would you like to know?", NamedTextColor.YELLOW));
        player.sendMessage(Component.text(""));
        player.sendMessage(Component.text("1. Explain again", NamedTextColor.GREEN)
            .append(Component.text(" - Replay instructions", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("2. I'm lost", NamedTextColor.GOLD)
            .append(Component.text(" - Get a hint", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("3. Explain a concept", NamedTextColor.LIGHT_PURPLE)
            .append(Component.text(" - Learn terminology", NamedTextColor.GRAY)));
        player.sendMessage(Component.text(""));
        player.sendMessage(Component.text("Type a number in chat to choose", NamedTextColor.DARK_GRAY, net.kyori.adventure.text.format.TextDecoration.ITALIC));
        player.sendMessage(Component.text("════════════════════════════════", NamedTextColor.GOLD));
    }
}