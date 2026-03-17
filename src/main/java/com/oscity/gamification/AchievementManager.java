package com.oscity.gamification;

import com.oscity.config.ConfigManager;
import com.oscity.session.SessionManager;
import com.oscity.session.SessionStats;
import com.oscity.persistence.SQLiteStudyDatabase;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.logging.Logger;

/**
 * Manages session-only achievements for the educational game.
 * Achievements are tracked in-memory and reset when session ends.
 * All unlocks are logged to the database for study analysis.
 */
public class AchievementManager {
    private static final Logger log = Logger.getLogger("OSCity");
    private final SessionManager sessionManager;
    private final ConfigManager configManager;
    private final Map<String, Set<String>> unlockedAchievements = new HashMap<>();

    public AchievementManager(SessionManager sessionManager, ConfigManager configManager) {
        this.sessionManager = sessionManager;
        this.configManager  = configManager;
    }
    
    /** Call when journey STARTS */
    public void onJourneyStart(Player player) {
        sessionManager.getStats().onStartJourney();
    }
    
    /** Call when journey COMPLETES */
    public void onJourneyComplete(Player player, String journeyName) {
        SessionStats stats = sessionManager.getStats();
        stats.onJourneyComplete(journeyName);
        log.info("[Achievement] onJourneyComplete: journey=" + journeyName
            + " | total=" + stats.completedJourneys.size()
            + " | wrongAnswers=" + stats.currentJourneyWrongAnswers
            + " | hints=" + stats.currentJourneyHints
            + " | perfectStreak=" + stats.perfectJourneyStreak
            + " | correctStreak=" + stats.currentCorrectStreak);
        
        // Understanding achievements
        checkUnlock(player, "first_steps", stats.journeysCompleted >= 1);
        checkUnlock(player, "explorer", stats.completedJourneys.size() >= 3);
        checkUnlock(player, "scholar", stats.completedJourneys.size() >= 7);
        
        // TLB Expert: lucky + any other journey
        boolean tlbExpert = stats.completedLucky && stats.completedJourneys.size() >= 2;
        checkUnlock(player, "tlb_expert", tlbExpert);
        
        // COW Understander: pure_cow OR lazy_allocation
        boolean cowUnderstander = stats.completedPureCOW || stats.completedLazyAllocation;
        checkUnlock(player, "cow_understander", cowUnderstander);
        
        // Laziness Pro: lazy_loading AND lazy_allocation
        boolean lazinessPro = stats.completedLazyLoading && stats.completedLazyAllocation;
        checkUnlock(player, "laziness_pro", lazinessPro);
        
        // Swap Master
        checkUnlock(player, "swap_master", stats.completedSwappedOut);
        
        // Accuracy achievements (check current journey)
        if (stats.currentJourneyWrongAnswers == 0) {
            checkUnlock(player, "flawless_journey", true);
        }
        if (stats.currentJourneyHints <= 2) {
            checkUnlock(player, "quick_learner", true);
        }
        
        // Perfect Run: 3 perfect journeys in a row
        checkUnlock(player, "perfect_run", stats.perfectJourneyStreak >= 3);
        
        // Show progress summary
        showProgress(player);
    }
    
    /** Call on correct answer */
    public void onCorrectAnswer(Player player) {
        SessionStats stats = sessionManager.getStats();
        stats.onCorrectAnswer();
        
        // Streak achievements
        checkUnlock(player, "on_fire", stats.currentCorrectStreak >= 5);
        checkUnlock(player, "unstoppable", stats.currentCorrectStreak >= 10);
    }
    
    /** Call on wrong answer */
    public void onWrongAnswer(Player player, String context) {
        sessionManager.getStats().onWrongAnswer();
        SQLiteStudyDatabase.logWrongAnswer(sessionManager.getSessionId(), context);
        player.sendMessage(configManager.getMessage("feedback.wrong_streak_reset"));
    }
    
    /** Call when hint is used */
    public void onHintUsed(Player player) {
        sessionManager.getStats().onHintUsed();
    }
    
    /** Call when player requests a concept explanation */
    public void onExplanationRequested(Player player) {
        SessionStats stats = sessionManager.getStats();
        stats.onExplanationRequested();
        checkUnlock(player, "curious_mind",    stats.explanationsRequested >= 5);
        checkUnlock(player, "kernel_scholar",  stats.explanationsRequested >= 10);
    }

    /** Call when swap clock algorithm completes */
    public void onSwapClockComplete(Player player, boolean perfect) {
        SessionStats stats = sessionManager.getStats();
        stats.swapClocksCompleted++;
        if (perfect) {
            stats.swapClockPerfectRuns++;
        }
        checkUnlock(player, "swap_master", stats.swapClocksCompleted >= 1);
    }
    
    /** Check and unlock achievement */
    private void checkUnlock(Player player, String achievementName, boolean condition) {
        if (!condition) return;

        String sessionId = sessionManager.getSessionId();
        unlockedAchievements.putIfAbsent(sessionId, new HashSet<>());
        Set<String> sessionUnlocked = unlockedAchievements.get(sessionId);

        if (!sessionUnlocked.contains(achievementName)) {
            sessionUnlocked.add(achievementName);
            log.info("[Achievement] UNLOCKED: " + achievementName + " | player=" + player.getName());
            SQLiteStudyDatabase.logAchievement(sessionId, achievementName);
            player.sendMessage(configManager.getMessage("feedback.achievement_unlock", "{name}", achievementName));
        } else {
            log.info("[Achievement] already unlocked: " + achievementName + " | player=" + player.getName());
        }
    }
    
    /** Show progress report */
    public void showProgress(Player player) {
        player.sendMessage(sessionManager.getStats().getProgressReport());
    }
}
