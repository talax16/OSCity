package com.oscity.mode;

import org.bukkit.entity.Player;

/**
 * Encapsulates mode-specific behaviour for Learner and Adventurer modes.
 *
 * Each implementation handles:
 *  - Journey assignment (auto vs player-chosen)
 *  - Chest key for placing the journey map
 *  - Whether the Kernel Guardian provides hints
 */
public interface ModeHandler {

    /**
     * Called when the player physically enters this mode's zone in the Terminal.
     * Implementations should set the player's mode, assign a journey if needed,
     * deliver the opening dialogue, and place the journey map.
     */
    void onModeEntered(Player player);

    /** Key under {@code chests.*} in config.yml where the journey map is placed. */
    String getMapChestKey();

    /** Whether the Kernel Guardian delivers hints in this mode. */
    boolean isHintsEnabled();
}
