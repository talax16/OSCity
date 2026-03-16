package com.oscity.session;

import java.util.HashSet;
import java.util.Set;

/**
 * Tracks session-only statistics for achievements and progress.
 * All data is in-memory and resets when session ends.
 */
public class SessionStats {
    // Journey tracking
    public int journeysCompleted = 0;
    public Set<String> completedJourneys = new HashSet<>();
    
    // Specific journey tracking for achievements
    public boolean completedLucky = false;
    public boolean completedPureCOW = false;
    public boolean completedLazyAllocation = false;
    public boolean completedLazyLoading = false;
    public boolean completedSwappedOut = false;
    
    // Current journey tracking (resets each journey)
    public int currentJourneyWrongAnswers = 0;
    public int currentJourneyHints = 0;
    
    // Streaks
    public int currentCorrectStreak = 0;
    public int bestCorrectStreak = 0;
    public int perfectJourneyStreak = 0;  // Journeys completed with 0 errors
    
    // Swap clock tracking
    public int swapClocksCompleted = 0;
    public int swapClockPerfectRuns = 0;

    // Explanation seeking
    public int explanationsRequested = 0;
    
    /** Call when starting a new journey */
    public void onStartJourney() {
        currentJourneyWrongAnswers = 0;
        currentJourneyHints = 0;
    }
    
    /** Call when journey completes */
    public void onJourneyComplete(String journeyName) {
        journeysCompleted++;
        completedJourneys.add(journeyName);
        
        // Track specific journeys
        String journeyLower = journeyName.toLowerCase();
        if (journeyLower.equals("lucky_journey")) {
            completedLucky = true;
        } else if (journeyLower.equals("pure_cow")) {
            completedPureCOW = true;
        } else if (journeyLower.equals("lazy_allocation")) {
            completedLazyAllocation = true;
        } else if (journeyLower.equals("lazy_loading")) {
            completedLazyLoading = true;
        } else if (journeyLower.equals("swapped_out")) {
            completedSwappedOut = true;
        }
        
        // Update perfect journey streak
        if (currentJourneyWrongAnswers == 0) {
            perfectJourneyStreak++;
        } else {
            perfectJourneyStreak = 0;
        }
    }
    
    /** Call on correct answer */
    public void onCorrectAnswer() {
        currentCorrectStreak++;
        if (currentCorrectStreak > bestCorrectStreak) {
            bestCorrectStreak = currentCorrectStreak;
        }
    }
    
    /** Call on wrong answer */
    public void onWrongAnswer() {
        currentJourneyWrongAnswers++;
        currentCorrectStreak = 0;
    }
    
    /** Call when hint is used */
    public void onHintUsed() {
        currentJourneyHints++;
    }

    /** Call when player requests a concept explanation */
    public void onExplanationRequested() {
        explanationsRequested++;
    }
    
    /** Get progress report */
    public String getProgressReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("§6§m════════════════════════════════════════\n");
        sb.append("§6§l  Session Progress\n");
        sb.append("§e━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("§f Journeys: §e" + journeysCompleted + " completed\n");
        sb.append("§f Different: §e" + completedJourneys.size() + "/7\n");
        sb.append("§f Correct Streak: §e" + currentCorrectStreak + "\n");
        sb.append("§f Best Streak: §6" + bestCorrectStreak + "\n");
        sb.append("§f Perfect Journey Streak: §a" + perfectJourneyStreak + "\n");
        sb.append("§e━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("§6§l  Achievement Progress\n");
        sb.append("§f Explorer: §e" + completedJourneys.size() + "/3 journeys\n");
        sb.append("§f Scholar: §e" + completedJourneys.size() + "/7 journeys\n");
        
        // TLB Expert: lucky + any other journey
        int tlbTypes = 0;
        if (completedLucky && completedJourneys.size() >= 2) {
            tlbTypes = 2;
        } else if (completedLucky || !completedJourneys.isEmpty()) {
            tlbTypes = 1;
        }
        sb.append("§f TLB Expert: §e" + tlbTypes + "/2 types\n");
        
        // Laziness Pro: lazy_loading AND lazy_allocation
        int lazinessCount = (completedLazyLoading ? 1 : 0) + (completedLazyAllocation ? 1 : 0);
        sb.append("§f Laziness Pro: §e" + lazinessCount + "/2 journeys\n");
        
        // Streaks
        sb.append("§f On Fire: §e" + currentCorrectStreak + "/5 streak\n");
        sb.append("§f Unstoppable: §e" + currentCorrectStreak + "/10 streak\n");
        sb.append("§f Perfect Run: §e" + perfectJourneyStreak + "/3 journeys\n");
        sb.append("§f Curious Mind: §e" + explanationsRequested + "/5 explanations\n");
        sb.append("§f Kernel Scholar: §e" + explanationsRequested + "/10 explanations\n");
        sb.append("§6§m════════════════════════════════════════");
        return sb.toString();
    }
}
