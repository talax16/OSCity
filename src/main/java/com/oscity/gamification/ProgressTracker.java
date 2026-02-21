package com.oscity.gamification;

import com.oscity.persistence.JourneyDAO;
import com.oscity.session.Journey;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Tracks which journeys each player has completed.
 *
 * Rules:
 *   - Journey #1 (LUCKY) is always available.
 *   - Journey N unlocks only after journey N-1 is marked complete.
 *   - Completion is persisted to SQLite via JourneyDAO so it survives restarts.
 *   - An in-memory cache (loaded on player join) keeps queries off the hot path.
 */
public class ProgressTracker {

    /** In-memory cache: uuid → set of completed journeys. */
    private final Map<UUID, Set<Journey>> completed = new HashMap<>();

    // ── Called on player join ─────────────────────────────────────────────────

    /**
     * Load this player's completion data from SQLite into the cache.
     * Call this as early as possible (e.g. PlayerJoinEvent).
     */
    public void loadPlayer(UUID uuid) {
        completed.put(uuid, JourneyDAO.loadCompleted(uuid));
    }

    /** Remove cached data when a player leaves to avoid memory leaks. */
    public void unloadPlayer(UUID uuid) {
        completed.remove(uuid);
    }

    // ── Completion ────────────────────────────────────────────────────────────

    /**
     * Mark a journey as complete for this player.
     * Updates both the in-memory cache and the SQLite DB.
     */
    public void markComplete(Player player, Journey journey) {
        UUID uuid = player.getUniqueId();
        completed.computeIfAbsent(uuid, k -> EnumSet.noneOf(Journey.class)).add(journey);
        JourneyDAO.markComplete(uuid, journey);
    }

    public boolean isComplete(Player player, Journey journey) {
        return completed.getOrDefault(player.getUniqueId(), Collections.emptySet()).contains(journey);
    }

    // ── Unlocking ─────────────────────────────────────────────────────────────

    /**
     * Journey N is unlocked when journey N-1 is complete (journey #1 always unlocked).
     */
    public boolean isUnlocked(Player player, Journey journey) {
        if (journey.number == 1) return true;
        Journey prev = Journey.fromNumber(journey.number - 1);
        return prev != null && isComplete(player, prev);
    }

    // ── Query helpers ─────────────────────────────────────────────────────────

    /** All journeys this player has finished. */
    public Set<Journey> getCompleted(Player player) {
        return Collections.unmodifiableSet(
            completed.getOrDefault(player.getUniqueId(), Collections.emptySet()));
    }

    /**
     * The next journey to play: first unlocked and not yet completed.
     * Returns null if all 7 are done.
     */
    public Journey nextJourney(Player player) {
        for (Journey j : Journey.values()) {
            if (!isComplete(player, j)) return j;
        }
        return null;
    }

    /** True only if every journey is marked complete. */
    public boolean allComplete(Player player) {
        return completed.getOrDefault(player.getUniqueId(), Collections.emptySet())
                        .size() == Journey.values().length;
    }
}
