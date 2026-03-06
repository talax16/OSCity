package com.oscity.mode;

import com.oscity.content.DialogueManager;
import com.oscity.journey.Journey;
import com.oscity.mechanics.JourneyMapManager;
import com.oscity.session.JourneyTracker;
import org.bukkit.entity.Player;

/**
 * Handles Learner Mode entry.
 *
 * Differences from Adventurer Mode:
 *  - Journey is auto-assigned (random) — the player does not choose.
 *  - Map is placed in {@code learnerChest}.
 *  - Kernel Guardian hints are enabled.
 *  - Phase is set to {@code terminal_journey_chosen} immediately,
 *    skipping the journey-selection step entirely.
 */
public class LearnerModeHandler implements ModeHandler {

    private final JourneyTracker tracker;
    private final DialogueManager dialogue;
    private final JourneyMapManager journeyMapManager;

    public LearnerModeHandler(JourneyTracker tracker,
                               DialogueManager dialogue,
                               JourneyMapManager journeyMapManager) {
        this.tracker = tracker;
        this.dialogue = dialogue;
        this.journeyMapManager = journeyMapManager;
    }

    @Override
    public void onModeEntered(Player player) {
        tracker.setMode(player, PlayerMode.LEARNER);

        // Get the next learner journey number (1-7, cycles back to 1 after 7)
        String learnerJourneyStr = tracker.getVar(player, "learnerJourneyNum");
        int currentJourneyNum = (learnerJourneyStr != null && !"?".equals(learnerJourneyStr))
            ? Integer.parseInt(learnerJourneyStr)
            : 0;
        
        // Increment and wrap around (1→2→3→4→5→6→7→1...)
        int nextJourneyNum = (currentJourneyNum % 7) + 1;
        tracker.setVar(player, "learnerJourneyNum", String.valueOf(nextJourneyNum));
        
        // Get journey by number
        Journey j = Journey.fromNumber(nextJourneyNum);
        if (j == null) j = Journey.LUCKY; // Fallback to Lucky if something goes wrong
        
        tracker.setJourney(player, j);
        tracker.setPhase(player, "terminal_journey_chosen");

        dialogue.speak(player, "rooms.terminal.enter_learner", tracker.getVars(player));

        // Place the rendered journey map in the learner chest
        journeyMapManager.giveInitialMap(player, getMapChestKey());
    }

    @Override
    public String getMapChestKey() {
        return "learnerChest";
    }

    @Override
    public boolean isHintsEnabled() {
        return true;
    }
}
