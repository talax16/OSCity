package com.oscity.mode;

import com.oscity.content.DialogueManager;
import com.oscity.session.JourneyTracker;
import org.bukkit.entity.Player;

/**
 * Handles Adventurer Mode entry.
 *
 * Differences from Learner Mode:
 *  - All 7 journeys are unlocked — the player selects one by typing 1–7 (or 8 for random).
 *  - Map is placed in {@code adventurerChest} after the player makes their choice.
 *  - Kernel Guardian hints are disabled (challenge mode).
 *  - Phase is set to {@code adventurer_select} so ChoiceButtonHandler awaits chat input.
 *
 * Note: {@code showJourneyList} is still invoked from RoomChangeListener (after a short
 * delay) — this handler only handles the immediate entry logic.
 */
public class AdventurerModeHandler implements ModeHandler {

    private final JourneyTracker tracker;
    private final DialogueManager dialogue;

    public AdventurerModeHandler(JourneyTracker tracker, DialogueManager dialogue) {
        this.tracker = tracker;
        this.dialogue = dialogue;
    }

    @Override
    public void onModeEntered(Player player) {
        tracker.setMode(player, PlayerMode.ADVENTURER);
        tracker.setPhase(player, "adventurer_select");
        dialogue.speak(player, "rooms.terminal.enter_adventurer", tracker.getVars(player));
        // showJourneyList is called by RoomChangeListener after a 2-second delay
    }

    @Override
    public String getMapChestKey() {
        return "adventurerChest";
    }

    @Override
    public boolean isHintsEnabled() {
        return false;
    }
}
