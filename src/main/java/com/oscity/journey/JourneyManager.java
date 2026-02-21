package com.oscity.journey;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralises routing decisions that depend on the active Journey.
 * Called by RoomChangeListener and ChoiceButtonHandler in place of
 * scattered {@code if (journey == Journey.X)} branches.
 */
public class JourneyManager {

    private JourneyManager() {}

    // ── RAM Room ──────────────────────────────────────────────────────────────

    /** Next phase after the player confirms a frame in the RAM Room (allow_access). */
    public static String nextPhaseAfterRamConfirm(Journey j) {
        return j == Journey.LUCKY ? "ram_finish" : "ram_retry_tlb_miss";
    }

    /** RAM mix-sign text [l1, l2, l3, l4] after allow_access confirm. */
    public static String[] ramSignAfterConfirm(Journey j) {
        return j == Journey.LUCKY
            ? new String[]{"FINISH", "", "", ""}
            : new String[]{"RETRY", "INSTRUCTION", "", ""};
    }

    /** After COW: true when the journey still needs a Swap District visit (Lazy Allocation). */
    public static boolean needsSwapAfterCow(Journey j) {
        return j == Journey.LAZY_ALLOCATION;
    }

    // ── Disk Room ─────────────────────────────────────────────────────────────

    /** Phase to set on Disk Room entry for this journey, or null if not applicable. */
    public static String diskPhase(Journey j) {
        if (j == Journey.LAZY_LOADING) return "disk_lazy_loading";
        if (j == Journey.SWAPPED_OUT)  return "disk_swap_retrieval";
        return null;
    }

    /** Follow-up dialogue path shown after the initial Disk Room spawn dialogue. */
    public static String diskPromptDialogue(Journey j) {
        if (j == Journey.LAZY_LOADING) return "rooms.disk_room.lazy_loading_prompt";
        if (j == Journey.SWAPPED_OUT)  return "rooms.disk_room.swap_retrieval_prompt";
        return null;
    }

    // ── Swap District ─────────────────────────────────────────────────────────

    /**
     * Variable updates to apply when entering the Swap District.
     * {@code currentPfnCow} is the current value of the "pfnCow" var
     * (used only for Lazy Allocation; ignored otherwise).
     * Returns a map of varName → newValue; may be empty.
     */
    public static Map<String, String> swapEntryVarUpdates(Journey j, String currentPfnCow) {
        Map<String, String> updates = new LinkedHashMap<>();
        if (j == Journey.LAZY_LOADING) {
            updates.put("slot", "1");
        } else if (j == Journey.LAZY_ALLOCATION) {
            updates.put("pfn",  currentPfnCow);
            updates.put("slot", "0");
        }
        return updates;
    }

    // ── RAM Room (returning from Swap) ────────────────────────────────────────

    /** Phase to set when returning to RAM from the Swap District, or null. */
    public static String phaseAfterSwapInRam(Journey j) {
        if (j == Journey.LAZY_LOADING)    return "swap_lazy_loading";
        if (j == Journey.LAZY_ALLOCATION) return "swap_lazy_alloc";
        return null;
    }

    /** Dialogue path for RAM after returning from the Swap District, or null. */
    public static String dialogueAfterSwapInRam(Journey j) {
        if (j == Journey.LAZY_LOADING)    return "rooms.ram_room.after_swap_for_lazy_loading";
        if (j == Journey.LAZY_ALLOCATION) return "rooms.ram_room.after_swap_for_lazy_alloc";
        return null;
    }

    // ── Lazy Allocation Room (second visit) ───────────────────────────────────

    /**
     * Var updates for the second Lazy Allocation Room visit (instruction changes to write).
     * Returns a map of varName → newValue; empty for all other journeys.
     */
    public static Map<String, String> lazyAllocSecondVisitVarUpdates(Journey j) {
        Map<String, String> updates = new LinkedHashMap<>();
        if (j == Journey.LAZY_ALLOCATION) {
            updates.put("instruction", "write 0x45 hello");
            updates.put("operation",   "write");
        }
        return updates;
    }
}
