package com.oscity.mechanics;

import com.oscity.content.DialogueManager;
import com.oscity.persistence.SQLiteStudyDatabase;
import com.oscity.session.JourneyTracker;
import com.oscity.session.SessionManager;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Delivers contextual hints to the player when they click "I'm lost".
 * Hints are loaded from dialogue.yml under the "hints" section.
 * The correct hint key is resolved from the player's current phase.
 *
 * For ADVENTURER mode: No hints are given - players must figure things out themselves.
 * For LEARNER mode: Full hints are provided.
 */
public class HintSystem {

    private static final Logger log = Logger.getLogger("OSCity");
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
        log.info("[Hint] " + player.getName() + " requested hint | phase=" + phase
            + " | resolved=" + (hintPath != null ? hintPath : "none"));

        if (hintPath != null && dialogueManager.hasPath(hintPath)) {
            dialogueManager.speak(player, hintPath, journeyTracker.getVars(player));
        } else {
            log.info("[Hint] No hint found for phase=" + phase);
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
            case "calculator_from_tlb_done":
                return "hints.calculator_room.from_tlb_after";
            case "calculator_from_lazy_loading":
                return "hints.calculator_room.from_lazy_loading";
            case "calculator_from_lazy_loading_done":
                return "hints.calculator_room.from_tlb_after";

            case "tlb_miss_correct":
                return "hints.tlb_room.before_enter_page_table";
            case "library_entrance":
            case "page_directory":
                return "hints.page_table_library.page_directory";
            case "correct_floor":
                return "hints.page_table_library.correct_floor";
            case "acquired_pte":
                return "hints.page_table_library.acquired_pte";
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
            case "lazy_alloc_before_tp":
                return "hints.lazy_allocation_room.go_to_cow";

            case "cow_decision":
                return "hints.cow_room.lost";
            case "cow_decision_after":
                return "hints.cow_room.go_to_ram";

            case "lazy_loading_entered":
                return "hints.lazy_loading_room.clues";
            case "lazy_loading_returned":
                return "hints.lazy_loading_room.after_calculator";

            case "disk_lazy_loading":
                return "hints.disk_room.lazy_loading";
            case "disk_lazy_loading_after_book":
                return "hints.disk_room.lazy_loading_after_book";
            case "disk_swap_retrieval":
                return "hints.disk_room.swap_out";
            case "disk_swap_retrieval_after_book":
                return "hints.disk_room.lazy_loading_after_book";

            case "ram_tlb_hit_access":
            case "ram_tlb_miss_access":
                return "hints.ram_room.general";
            case "ram_disk_swap":
            case "ram_after_swap_lazy_loading":
                return "hints.ram_room.place_book";
            case "ram_book_placed_swapped":
            case "ram_book_placed_pure_cow":
            case "ram_book_placed_lazy_loading":
            case "ram_book_placed_lazy_allocation":
                return "hints.ram_room.retry_instruction";
            case "ram_after_cow":
            case "ram_after_swap_lazy_alloc":
                return "hints.ram_room.write";
            case "ram_disk_lazy_loading":
            case "ram_after_cow_alloc":
                return "hints.ram_room.swap";
            case "ram_before_finish":
            case "ram_finish":
                return "hints.ram_room.finish";

            case "swap_entered":
                return "hints.swap_district.lost";
            case "swap_victim_found":
                return "hints.swap_district.victim_found";
            case "swap_after_eviction":
                return "hints.swap_district.victim_evicted";

            default:
                return null;
        }
    }
}
