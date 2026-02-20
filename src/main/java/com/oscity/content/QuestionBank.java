package com.oscity.content;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads questions.yml and serves quiz questions by path.
 *
 * Multiple-choice questions have an options map (A→text, B→text…).
 * Simple questions (e.g. "type 1 or 2") have options = null.
 *
 * The correct field is the answer key (e.g. "B") or value (e.g. "1").
 * Comparison is case-insensitive.
 */
public class QuestionBank {

    private final JavaPlugin plugin;
    private FileConfiguration questions;

    public QuestionBank(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "questions.yml");
        if (!file.exists()) {
            plugin.saveResource("questions.yml", false);
        }
        questions = YamlConfiguration.loadConfiguration(file);
        plugin.getLogger().info("QuestionBank: loaded questions.yml");
    }

    // ── Question model ────────────────────────────────────────────────────────

    public static class Question {
        public final String text;
        public final Map<String, String> options; // null for simple input
        public final String correctAnswer;
        public final String wrongFeedback;

        public Question(String text, Map<String, String> options,
                        String correctAnswer, String wrongFeedback) {
            this.text = text;
            this.options = options;
            this.correctAnswer = correctAnswer;
            this.wrongFeedback = wrongFeedback;
        }

        public boolean isMultipleChoice() {
            return options != null && !options.isEmpty();
        }

        public boolean checkAnswer(String playerAnswer) {
            return correctAnswer.equalsIgnoreCase(playerAnswer.trim());
        }

        /** Build a formatted multiple-choice display string. */
        public String formatOptions(Map<String, String> vars) {
            if (options == null) return "";
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : options.entrySet()) {
                String line = e.getValue();
                if (vars != null) {
                    for (Map.Entry<String, String> v : vars.entrySet()) {
                        line = line.replace("{" + v.getKey() + "}", v.getValue());
                    }
                }
                sb.append("§e").append(e.getKey()).append(") §f").append(line).append("\n");
            }
            return sb.toString().trim();
        }
    }

    // ── Lookup ────────────────────────────────────────────────────────────────

    /**
     * Get a question by YAML path (e.g. "tlb_room.miss_door").
     * Returns null and logs a warning if not found.
     */
    public Question getQuestion(String path) {
        ConfigurationSection sec = questions.getConfigurationSection(path);
        if (sec == null) {
            plugin.getLogger().warning("QuestionBank: no question at '" + path + "'");
            return null;
        }

        String text = sec.getString("question", "");
        String correct = sec.getString("correct", "");
        String wrong = sec.getString("wrong", "That's incorrect, try again.");

        Map<String, String> options = null;
        ConfigurationSection optSec = sec.getConfigurationSection("options");
        if (optSec != null) {
            options = new LinkedHashMap<>();
            for (String key : optSec.getKeys(false)) {
                options.put(key, optSec.getString(key, ""));
            }
        }

        return new Question(text, options, correct, wrong);
    }
}
