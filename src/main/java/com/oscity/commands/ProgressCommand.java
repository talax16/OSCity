package com.oscity.commands;

import com.oscity.gamification.AchievementManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Command to show achievement progress: /progress
 */
public class ProgressCommand implements CommandExecutor {
    private final AchievementManager achievementManager;
    
    public ProgressCommand(AchievementManager achievementManager) {
        this.achievementManager = achievementManager;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command!");
            return true;
        }
        
        achievementManager.showProgress(player);
        return true;
    }
}
