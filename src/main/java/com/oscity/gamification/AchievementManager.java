package com.oscity.gamification;

import com.oscity.session.SessionManager;
import com.oscity.persistence.SQLiteStudyDatabase;
import org.bukkit.entity.Player;

public class AchievementManager {

    private SessionManager sessionManager;

    public AchievementManager(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * Called when an achievement should be unlocked for the player.
     */
    public void unlockAchievement(Player player, String achievementName) {
        player.sendMessage("Achievement Unlocked: " + achievementName);
        SQLiteStudyDatabase.logAchievement(sessionManager.getSessionId(), achievementName);
    }
}
