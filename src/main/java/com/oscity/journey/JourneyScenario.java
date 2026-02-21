package com.oscity.journey;

import java.util.Map;

/**
 * Defines the data and routing behaviour for a single learning journey.
 * Each of the seven journeys has a dedicated implementation class.
 */
public interface JourneyScenario {

    // ── Static data ───────────────────────────────────────────────────────────

    String getDisplayName();
    int getNumber();
    boolean isTlbHit();

    /** Correct button key at the Permission Chamber (round 1). */
    String getPermissionAnswer();

    /** Correct page-fault subtype key at Permission Chamber (round 2), or null. */
    String getPageFaultType();

    // ── Variable initialisation ───────────────────────────────────────────────

    /**
     * Populate all journey-specific placeholder variables into the player's vars map.
     * Called once when the player selects this journey.
     */
    void initVars(Map<String, String> vars);

    // ── Routing flags ─────────────────────────────────────────────────────────

    /** True if this journey passes through the COW room. */
    boolean involvesCow();

    /** True if this journey requires visiting the Swap District. */
    boolean involvesSwap();
}
