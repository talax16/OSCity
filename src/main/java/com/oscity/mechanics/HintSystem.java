package com.oscity.mechanics;

import com.oscity.content.DialogueManager;
import com.oscity.persistence.SQLiteStudyDatabase;
import com.oscity.session.JourneyTracker;
import com.oscity.session.SessionManager;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Delivers contextual hints to the player when they click "I'm lost".
 * Hints are loaded from dialogue.yml under the "hints" section.
 * The correct hint key is resolved from the player's current phase.
 *
 * For ADVENTURER mode: No hints are given - players must figure things out themselves.
 * For LEARNER mode: Full hints are provided.
 */
public class HintSystem {

    private final SessionManager sessionManager;
    private final DialogueManager dialogueManager;
    private final JourneyTracker journeyTracker;
    private final com.oscity.config.ConfigManager configManager;

    /** Tracks how many hints each player has requested for phases with multiple hints. */
    private final Map<UUID, Integer> calcHintCounter = new HashMap<>();

    public HintSystem(SessionManager sessionManager,
                      DialogueManager dialogueManager,
                      JourneyTracker journeyTracker,
                      com.oscity.config.ConfigManager configManager) {
        this.sessionManager = sessionManager;
        this.dialogueManager = dialogueManager;
        this.journeyTracker = journeyTracker;
        this.configManager = configManager;
    }

    /**
     * Show the appropriate hint for the player's current phase.
     * Called when the player clicks "I'm lost" in the guardian menu.
     * 
     * For ADVENTURER mode: Returns a message encouraging independent problem-solving.
     * For LEARNER mode: Shows the normal hint.
     */
    public void showHint(Player player) {
        String phase = journeyTracker.getPhase(player);

        // For phases with multiple numbered hints, tell the player which hint they're on
        if ("calculator_from_tlb".equals(phase)) {
            int current = calcHintCounter.getOrDefault(player.getUniqueId(), 0);
            player.sendMessage(configManager.getMessage("feedback.hint_counter", "{current}", String.valueOf(current + 1), "{total}", "3"));
        }

        String hintPath = resolveHintPath(player, phase);

        if (hintPath != null && dialogueManager.hasPath(hintPath)) {
            dialogueManager.speak(player, hintPath, journeyTracker.getVars(player));
        } else {
            player.sendMessage(configManager.getMessage("feedback.hint_fallback"));
        }

        sessionManager.recordHintUsed();
        SQLiteStudyDatabase.logHintUsed(sessionManager.getSessionId(), phase);
        
        // Track for Quick Learner achievement
        sessionManager.getStats().onHintUsed();
    }

    // ── Phase → hint path mapping ─────────────────────────────────────────────

    private String resolveHintPath(Player player, String phase) {
        switch (phase) {
            case "terminal_spawn":
                return "hints.terminal.before_entering";

            case "tlb_spawn":
                return "hints.tlb_room.before_calculator";
            case "tlb_after_calculator":
                boolean isLucky = journeyTracker.getJourney(player) != null
                        && journeyTracker.getJourney(player).isTlbHit;
                return isLucky
                        ? "hints.tlb_room.after_calculator_lucky"
                        : "hints.tlb_room.after_calculator_non_lucky";

            case "calculator_from_tlb": {
                int count = calcHintCounter.getOrDefault(player.getUniqueId(), 0);
                calcHintCounter.put(player.getUniqueId(), (count + 1) % 3);
                return "hints.calculator_room.from_tlb_hint" + (count + 1);
            }
            case "calculator_from_lazy_loading":
                return "hints.calculator_room.from_lazy_loading";

            case "library_entrance":
            case "page_directory":
                return "hints.page_table_library.page_directory";
            case "correct_floor":
                return "hints.page_table_library.correct_floor";
            case "wrong_floor":
                return "hints.page_table_library.wrong_floor";

            case "permission_decision":
                return "hints.permission_chamber.decision";
            case "page_fault_type":
                return "hints.permission_chamber.page_fault_type";

            case "page_fault_corridor":
                return "hints.page_fault_corridor.lost";

            case "lazy_alloc_decision":
                return "hints.lazy_allocation_room.first_visit";
            case "lazy_alloc_cow":
                return "hints.lazy_allocation_room.second_visit";

            case "cow_decision":
                return "hints.cow_room.lost";

            case "lazy_loading_entered":
                return "hints.lazy_loading_room.clues";

            case "disk_lazy_loading":
                return "hints.disk_room.lazy_loading";
            case "disk_swap_retrieval":
                return "hints.disk_room.swap_out";

            default:
                if (phase.startsWith("ram_")) return "hints.ram_room.general";
                if (phase.startsWith("swap_")) return "hints.swap_district.lost";
                return null;
        }
    }
}
