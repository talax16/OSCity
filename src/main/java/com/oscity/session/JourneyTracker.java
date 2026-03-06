package com.oscity.session;

import com.oscity.journey.Journey;
import com.oscity.mode.PlayerMode;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks each player's current journey and phase.
 *
 * Phase keys map directly to dialogue.yml paths.
 * Vars are {placeholder} substitutions used in dialogue lines.
 *
 * Default phase "terminal_spawn" means the player just arrived
 * at the terminal and has not yet chosen a journey.
 */
public class JourneyTracker {

    public static class PlayerState {
        public Journey journey;
        public String phase = "terminal_spawn";
        public PlayerMode mode = null;
        public final Map<String, String> vars = new HashMap<>();

        /** Convenience: set a var and return self for chaining. */
        public PlayerState setVar(String key, String value) {
            vars.put(key, value);
            return this;
        }
    }

    private final Map<UUID, PlayerState> states = new HashMap<>();

    // ── State accessors ──────────────────────────────────────────────────────

    public PlayerState getState(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), k -> new PlayerState());
    }

    public void setJourney(Player player, Journey journey) {
        PlayerState state = getState(player);
        state.journey = journey;
        if (journey != null) {
            state.vars.put("journey", journey.displayName);
            journey.initVars(state.vars);
        } else {
            state.vars.remove("journey");
        }
    }

    public Journey getJourney(Player player) {
        return getState(player).journey;
    }

    public void setPhase(Player player, String phase) {
        getState(player).phase = phase;
    }

    public String getPhase(Player player) {
        return getState(player).phase;
    }

    public void setVar(Player player, String key, String value) {
        getState(player).vars.put(key, value);
    }

    public String getVar(Player player, String key) {
        return getState(player).vars.getOrDefault(key, "?");
    }

    public Map<String, String> getVars(Player player) {
        return getState(player).vars;
    }

    public void setMode(Player player, PlayerMode mode) {
        getState(player).mode = mode;
    }

    public PlayerMode getMode(Player player) {
        return getState(player).mode;
    }

    /**
     * Clear all journey vars (called when restarting journey).
     * Preserves session ID if it exists.
     */
    public void clearVars(Player player) {
        PlayerState state = getState(player);
        state.vars.clear();
    }

    /** Clear all state for this player (e.g. on journey restart). */
    public void reset(Player player) {
        states.remove(player.getUniqueId());
    }
}
