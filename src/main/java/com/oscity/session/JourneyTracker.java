package com.oscity.session;

import com.oscity.journey.Journey;
import com.oscity.mode.PlayerMode;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
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

    /** One answered question from the assessment quiz. */
    public static class QuizResult {
        public final Journey journey;
        public final String question;
        public final String chosenAnswer;   // e.g. "A"
        public final String correctAnswer;  // e.g. "B"
        public final boolean correct;

        public QuizResult(Journey journey, String question,
                          String chosenAnswer, String correctAnswer) {
            this.journey       = journey;
            this.question      = question;
            this.chosenAnswer  = chosenAnswer;
            this.correctAnswer = correctAnswer;
            this.correct       = chosenAnswer.equalsIgnoreCase(correctAnswer);
        }
    }

    public static class PlayerState {
        public Journey journey;
        public String phase = "terminal_spawn";
        public PlayerMode mode = null;
        public final Map<String, String> vars = new HashMap<>();

        // ── Assessment quiz state ────────────────────────────────────────────
        /** Wrong-answer count per journey (reset when quiz is retaken). */
        public final Map<Journey, Integer> quizWrongCounts = new EnumMap<>(Journey.class);
        /** Full ordered record of every answered quiz question. */
        public final List<JourneyTracker.QuizResult> quizResults = new ArrayList<>();

        // ── Terminal path selection state ────────────────────────────────────
        /** Tracks position in the "all journeys in order" path (1–7, 0 = not started). */
        public int allInOrderIndex = 0;

        /** Convenience: set a var and return self for chaining. */
        public PlayerState setVar(String key, String value) {
            vars.put(key, value);
            return this;
        }

        /** Reset quiz data so a retake starts clean. */
        public void resetQuiz() {
            quizWrongCounts.clear();
            quizResults.clear();
        }
    }

    private final Map<UUID, PlayerState> states = new HashMap<>();

    // ── State accessors ──────────────────────────────────────────────────────

    public PlayerState getState(Player player) {
        PlayerState state = states.computeIfAbsent(player.getUniqueId(), k -> new PlayerState());
        state.vars.put("player", player.getName());
        return state;
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

    // ── Assessment quiz accessors ─────────────────────────────────────────────

    /**
     * Record one answered quiz question.
     * Increments the wrong count for the journey if the answer was incorrect.
     */
    public void recordQuizAnswer(Player player, Journey journey, String question,
                                  String chosen, String correct) {
        PlayerState state = getState(player);
        QuizResult result = new QuizResult(journey, question, chosen, correct);
        state.quizResults.add(result);
        if (!result.correct) {
            state.quizWrongCounts.merge(journey, 1, Integer::sum);
        }
    }

    /** Returns the wrong-answer count for a specific journey. */
    public int getQuizWrongCount(Player player, Journey journey) {
        return getState(player).quizWrongCounts.getOrDefault(journey, 0);
    }

    /** Returns the full wrong-count map (Journey → wrong answers). */
    public Map<Journey, Integer> getQuizWrongCounts(Player player) {
        return getState(player).quizWrongCounts;
    }

    /** Returns the full ordered list of quiz results for the results screen. */
    public List<QuizResult> getQuizResults(Player player) {
        return getState(player).quizResults;
    }

    /**
     * Returns the recommended journey(ies) based on quiz results:
     * - All correct → empty list (caller handles the "apply your knowledge" message)
     * - Otherwise → journey(ies) with the highest wrong-answer count
     */
    public List<Journey> getRecommendedJourneys(Player player) {
        Map<Journey, Integer> counts = getState(player).quizWrongCounts;
        if (counts.isEmpty()) return new ArrayList<>();

        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (max == 0) return new ArrayList<>();

        List<Journey> recommended = new ArrayList<>();
        for (Journey j : Journey.values()) {
            if (counts.getOrDefault(j, 0) == max) recommended.add(j);
        }
        return recommended;
    }

    /** Clears quiz data so a retake starts fresh (overwrites previous results). */
    public void resetQuiz(Player player) {
        getState(player).resetQuiz();
    }

    // ── Terminal path selection ───────────────────────────────────────────────

    /** Returns true if the player has completed the assessment quiz at least once. */
    public boolean hasCompletedQuiz(Player player) {
        return !getState(player).quizResults.isEmpty();
    }

    /** Returns the current "all in order" journey index (0 = not started, 1–7 = current). */
    public int getAllInOrderIndex(Player player) {
        return getState(player).allInOrderIndex;
    }

    /** Sets the "all in order" journey index directly. */
    public void setAllInOrderIndex(Player player, int index) {
        getState(player).allInOrderIndex = index;
    }
}
