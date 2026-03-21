package com.oscity.session;

import org.bukkit.configuration.file.FileConfiguration;

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
    public int perfectRun = 0;  // Journeys completed with 0 errors (cumulative, never resets)

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

        // Update perfect journey count (cumulative, never resets)
        if (currentJourneyWrongAnswers == 0) {
            perfectRun++;
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
    public String getProgressReport(FileConfiguration achievements) {
        StringBuilder sb = new StringBuilder();
        sb.append("§6§m════════════════════════════════════════\n");
        sb.append("§6§l  Achievements\n");

        // Helper to get display name and description
        java.util.function.Function<String, String[]> getAchievementInfo = (key) -> {
            String display = achievements != null ? achievements.getString(key + ".display", key) : key;
            String desc = achievements != null ? achievements.getString(key + ".description", "") : "";
            return new String[]{display, desc};
        };

        // Understanding achievements
        sb.append("\n§e§l  Understanding:\n");
        String[] firstSteps = getAchievementInfo.apply("first_steps");
        sb.append("§f " + firstSteps[0] + " §8- §7" + firstSteps[1] + "\n");
        
        String[] explorer = getAchievementInfo.apply("explorer");
        sb.append("§f " + explorer[0] + " §8- §7" + explorer[1] + "\n");
        
        String[] scholar = getAchievementInfo.apply("scholar");
        sb.append("§f " + scholar[0] + " §8- §7" + scholar[1] + "\n");
        
        String[] tlbExpert = getAchievementInfo.apply("tlb_expert");
        sb.append("§f " + tlbExpert[0] + " §8- §7" + tlbExpert[1] + "\n");
        
        String[] cowUnderstander = getAchievementInfo.apply("cow_understander");
        sb.append("§f " + cowUnderstander[0] + " §8- §7" + cowUnderstander[1] + "\n");
        
        String[] lazinessPro = getAchievementInfo.apply("laziness_pro");
        sb.append("§f " + lazinessPro[0] + " §8- §7" + lazinessPro[1] + "\n");
        
        String[] swapMaster = getAchievementInfo.apply("swap_master");
        sb.append("§f " + swapMaster[0] + " §8- §7" + swapMaster[1] + "\n");

        // Accuracy achievements
        sb.append("\n§e§l  Accuracy:\n");
        String[] flawless = getAchievementInfo.apply("flawless_journey");
        sb.append("§f " + flawless[0] + " §8- §7" + flawless[1] + "\n");
        
        String[] quickLearner = getAchievementInfo.apply("quick_learner");
        sb.append("§f " + quickLearner[0] + " §8- §7" + quickLearner[1] + "\n");

        // Streak achievements
        sb.append("\n§e§l  Streaks:\n");
        String[] onFire = getAchievementInfo.apply("on_fire");
        sb.append("§f " + onFire[0] + " §8- §7" + onFire[1] + "\n");
        
        String[] unstoppable = getAchievementInfo.apply("unstoppable");
        sb.append("§f " + unstoppable[0] + " §8- §7" + unstoppable[1] + "\n");
        
        String[] perfectRunAch = getAchievementInfo.apply("perfect_run");
        sb.append("§f " + perfectRunAch[0] + " §8- §7" + perfectRunAch[1] + "\n");

        // Curiosity achievements
        sb.append("\n§e§l  Curiosity:\n");
        String[] curiousMind = getAchievementInfo.apply("curious_mind");
        sb.append("§f " + curiousMind[0] + " §8- §7" + curiousMind[1] + "\n");
        
        String[] kernelScholar = getAchievementInfo.apply("kernel_scholar");
        sb.append("§f " + kernelScholar[0] + " §8- §7" + kernelScholar[1] + "\n");

        sb.append("\n§e━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("§6§l  Achievement Progress\n");

        // Understanding progress
        int firstStepsProgress = (journeysCompleted >= 1) ? 1 : 0;
        sb.append("§f " + firstSteps[0] + ": §e" + firstStepsProgress + "/1\n");
        
        int explorerProgress = Math.min(completedJourneys.size(), 3);
        sb.append("§f " + explorer[0] + ": §e" + explorerProgress + "/3\n");
        
        int scholarProgress = Math.min(completedJourneys.size(), 7);
        sb.append("§f " + scholar[0] + ": §e" + scholarProgress + "/7\n");
        
        int tlbExpertProgress = (completedLucky && completedJourneys.size() >= 2) ? 2 : (completedLucky || !completedJourneys.isEmpty() ? 1 : 0);
        sb.append("§f " + tlbExpert[0] + ": §e" + tlbExpertProgress + "/2\n");
        
        int cowUnderstanderProgress = (completedPureCOW || completedLazyAllocation) ? 1 : 0;
        sb.append("§f " + cowUnderstander[0] + ": §e" + cowUnderstanderProgress + "/1\n");
        
        int lazinessProProgress = (completedLazyLoading ? 1 : 0) + (completedLazyAllocation ? 1 : 0);
        sb.append("§f " + lazinessPro[0] + ": §e" + lazinessProProgress + "/2\n");
        
        int swapMasterProgress = completedSwappedOut ? 1 : 0;
        sb.append("§f " + swapMaster[0] + ": §e" + swapMasterProgress + "/1\n");

        // Accuracy progress
        int flawlessProgress = (currentJourneyWrongAnswers == 0 && journeysCompleted > 0) ? 1 : 0;
        sb.append("§f " + flawless[0] + ": §e" + flawlessProgress + "/1\n");
        
        int quickLearnerProgress = (currentJourneyHints <= 2 && journeysCompleted > 0) ? 1 : 0;
        sb.append("§f " + quickLearner[0] + ": §e" + quickLearnerProgress + "/1\n");

        // Streak progress
        int onFireProgress = Math.min(currentCorrectStreak, 5);
        sb.append("§f " + onFire[0] + ": §e" + onFireProgress + "/5\n");
        
        int unstoppableProgress = Math.min(currentCorrectStreak, 10);
        sb.append("§f " + unstoppable[0] + ": §e" + unstoppableProgress + "/10\n");
        
        int perfectRunProgress = Math.min(perfectRun, 3);
        sb.append("§f " + perfectRunAch[0] + ": §e" + perfectRunProgress + "/3\n");

        // Curiosity progress
        int curiousMindProgress = Math.min(explanationsRequested, 5);
        sb.append("§f " + curiousMind[0] + ": §e" + curiousMindProgress + "/5\n");
        
        int kernelScholarProgress = Math.min(explanationsRequested, 10);
        sb.append("§f " + kernelScholar[0] + ": §e" + kernelScholarProgress + "/10\n");

        sb.append("§6§m════════════════════════════════════════");
        return sb.toString();
    }
}
