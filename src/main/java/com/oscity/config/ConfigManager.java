package com.oscity.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

public class ConfigManager {

    private final JavaPlugin plugin;
    
    // Main config (already loaded by plugin)
    private FileConfiguration config;
    
    // Additional config files
    private FileConfiguration messages;
    private FileConfiguration questions;
    private FileConfiguration achievements;
    
    // The actual File objects (needed for saving)
    private File messagesFile;
    private File questionsFile;
    private File achievementsFile;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadAllConfigs();
    }

    /**
     * Load all configuration files
     */
    public void loadAllConfigs() {

        config = plugin.getConfig();
        
        messages = loadConfig("messages.yml", messagesFile);
        questions = loadConfig("questions.yml", questionsFile);
        achievements = loadConfig("achievements.yml", achievementsFile);
        
        plugin.getLogger().info("ConfigManager: All config files loaded successfully");
    }

    /**
     * Load a custom config file
     * @param fileName Name of the file
     * @param fileRef Reference to store the File object
     * @return The loaded FileConfiguration
     */
    private FileConfiguration loadConfig(String fileName, File fileRef) {
        
        fileRef = new File(plugin.getDataFolder(), fileName);
        
        // If file doesn't exist, copy it from resources
        if (!fileRef.exists()) {
            plugin.saveResource(fileName, false);
            plugin.getLogger().info("Created default " + fileName);
        }
        
        // Update the stored file reference
        switch (fileName) {
            case "messages.yml":
                messagesFile = fileRef;
                break;
            case "questions.yml":
                questionsFile = fileRef;
                break;
            case "achievements.yml":
                achievementsFile = fileRef;
                break;
        }
        
        return YamlConfiguration.loadConfiguration(fileRef);
    }

    /**
     * Reload all configuration files
     */
    public void reloadAllConfigs() {
        plugin.reloadConfig();
        config = plugin.getConfig();
        
        messages = YamlConfiguration.loadConfiguration(messagesFile);
        questions = YamlConfiguration.loadConfiguration(questionsFile);
        achievements = YamlConfiguration.loadConfiguration(achievementsFile);
        
        plugin.getLogger().info("ConfigManager: All configs reloaded");
    }

    /**
     * Save a specific config file
     */
    public void saveMessages() {
        saveConfig(messages, messagesFile, "messages.yml");
    }

    public void saveQuestions() {
        saveConfig(questions, questionsFile, "questions.yml");
    }

    public void saveAchievements() {
        saveConfig(achievements, achievementsFile, "achievements.yml");
    }

    private void saveConfig(FileConfiguration config, File file, String name) {
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + name, e);
        }
    }

    // ===== GETTERS =====
    
    /**
     * Get the main config.yml
     */
    public FileConfiguration getConfig() {
        return config;
    }

    /**
     * Get messages.yml
     */
    public FileConfiguration getMessages() {
        return messages;
    }

    /**
     * Get questions.yml
     */
    public FileConfiguration getQuestions() {
        return questions;
    }

    /**
     * Get achievements.yml
     */
    public FileConfiguration getAchievements() {
        return achievements;
    }

    // ===== CONVENIENCE METHODS =====
    
    /**
     * Get a message with color code support
     * @param path Path in messages.yml (e.g., "terminal.welcome")
     * @return The message with & color codes converted
     */
    public String getMessage(String path) {
        String msg = messages.getString(path);
        if (msg == null) {
            plugin.getLogger().warning("Missing message: " + path);
            return "§c[Missing message: " + path + "]";
        }
        // Convert & color codes to Minecraft format
        return msg.replace('&', '§');
    }

    /**
     * Get a message with placeholder replacement
     * @param path Path in messages.yml
     * @param placeholders Array of {placeholder, value} pairs
     * @return The message with placeholders replaced
     */
    public String getMessage(String path, String... placeholders) {
        String msg = getMessage(path);
        
        // Replace placeholders in pairs
        for (int i = 0; i < placeholders.length - 1; i += 2) {
            String placeholder = placeholders[i];
            String value = placeholders[i + 1];
            msg = msg.replace(placeholder, value);
        }
        
        return msg;
    }

    /**
     * Check if debug mode is enabled
     */
    public boolean isDebugMode() {
        return config.getBoolean("debugClicks", false);
    }

    /**
     * Check if room display is enabled
     */
    public boolean isRoomDisplayEnabled() {
        return config.getBoolean("roomDisplay.enabled", true);
    }

    /**
     * Get room display interval in ticks
     */
    public int getRoomDisplayInterval() {
        return config.getInt("roomDisplay.intervalTicks", 10);
    }
}