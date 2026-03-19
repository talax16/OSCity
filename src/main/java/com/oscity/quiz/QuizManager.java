package com.oscity.quiz;

import com.oscity.config.ConfigManager;
import com.oscity.content.QuestionBank;
import com.oscity.journey.Journey;
import com.oscity.persistence.SQLiteStudyDatabase;
import com.oscity.session.JourneyTracker;
import com.oscity.session.SessionManager;
import com.oscity.world.RoomRegistry;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Handles both in-game quiz validation (validateAnswer) and the full
 * 28-question assessment quiz sequence (startQuiz / onChat / showResults).
 */
public class QuizManager implements Listener {

    // ── Journey → YAML section key ────────────────────────────────────────────

    private static final Map<Journey, String> JOURNEY_SECTION = new EnumMap<>(Journey.class);
    private static final String[] Q_KEYS = {"q1", "q2", "q3", "q4"};

    static {
        JOURNEY_SECTION.put(Journey.LUCKY,                "lucky_journey");
        JOURNEY_SECTION.put(Journey.TLB_MISS_ALLOW,       "tlb_miss_no_fault");
        JOURNEY_SECTION.put(Journey.PERMISSION_VIOLATION,  "permission_violation");
        JOURNEY_SECTION.put(Journey.SWAPPED_OUT,          "swapped_out_page");
        JOURNEY_SECTION.put(Journey.PURE_COW,             "pure_cow");
        JOURNEY_SECTION.put(Journey.LAZY_LOADING,         "lazy_loading");
        JOURNEY_SECTION.put(Journey.LAZY_ALLOCATION,      "lazy_allocation");
    }

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final SessionManager sessionManager;
    private final RoomRegistry roomRegistry;
    private final QuestionBank questionBank;
    private final JourneyTracker journeyTracker;

    // ── Active assessment quiz sessions ───────────────────────────────────────

    private final Map<UUID, QuizSession> activeSessions = new HashMap<>();
    /** Players who have been prompted and are waiting to type 1 (yes) or 2 (no). */
    private final Set<UUID> awaitingConfirm = new HashSet<>();

    // ── Inner types ───────────────────────────────────────────────────────────

    private static class QuizQuestion {
        final Journey journey;
        final QuestionBank.Question question;

        QuizQuestion(Journey journey, QuestionBank.Question question) {
            this.journey  = journey;
            this.question = question;
        }
    }

    private static class QuizSession {
        final List<QuizQuestion> questions;
        int index = 0;

        QuizSession(List<QuizQuestion> questions) {
            this.questions = questions;
        }

        QuizQuestion current()  { return questions.get(index); }
        boolean      hasNext()  { return index < questions.size() - 1; }
        void         advance()  { index++; }
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    public QuizManager(JavaPlugin plugin, ConfigManager configManager,
                       SessionManager sessionManager,
                       RoomRegistry roomRegistry, QuestionBank questionBank,
                       JourneyTracker journeyTracker) {
        this.plugin         = plugin;
        this.configManager  = configManager;
        this.sessionManager = sessionManager;
        this.roomRegistry   = roomRegistry;
        this.questionBank   = questionBank;
        this.journeyTracker = journeyTracker;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("QuizManager registered.");
    }

    // ── Existing single-answer validator (used by ChoiceButtonHandler) ─────────

    /**
     * Validate a player's answer for a given question path (e.g. "tlb_room.miss_door").
     * Returns true if the answer is correct. Logs wrong answers to the database.
     */
    public boolean validateAnswer(Player player, String questionPath, String playerAnswer) {
        QuestionBank.Question question = questionBank.getQuestion(questionPath);
        if (question == null) return false;

        boolean isCorrect = question.checkAnswer(playerAnswer);

        if (isCorrect) {
            player.sendMessage(configManager.getMessage("feedback.correct"));
        } else {
            player.sendMessage("§c" + question.wrongFeedback);
            sessionManager.recordWrongAnswer();
            SQLiteStudyDatabase.logWrongAnswer(
                sessionManager.getSessionId(),
                getCurrentRoom(player)
            );
        }

        return isCorrect;
    }

    // ── Assessment quiz public API ────────────────────────────────────────────

    /** Start the 28-question assessment quiz. Resets any previous results. */
    public void startQuiz(Player player) {
        journeyTracker.resetQuiz(player);
        List<QuizQuestion> questions = buildShuffledQuestions();
        QuizSession session = new QuizSession(questions);
        activeSessions.put(player.getUniqueId(), session);

        player.sendMessage(configManager.getMessage("ui.separator"));
        player.sendMessage(configManager.getMessage("ui.quiz.title"));
        player.sendMessage(configManager.getMessage("ui.quiz.subtitle"));
        player.sendMessage(configManager.getMessage("ui.separator"));

        Bukkit.getScheduler().runTaskLater(plugin, () -> askQuestion(player, session), 20L);
    }

    public boolean isInQuiz(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    /**
     * Prompts the player to confirm they want to start (or reattempt) the quiz.
     * If they have previous results, warns them that results will be reset.
     */
    public void promptQuizStart(Player player) {
        boolean hasResults = journeyTracker.hasCompletedQuiz(player);
        player.sendMessage(configManager.getMessage("ui.separator"));
        if (hasResults) {
            player.sendMessage("§6[Kernel Guardian] §eYou have attempted this quiz before.");
            player.sendMessage("§6[Kernel Guardian] §7Starting again will §cerase your previous results");
            player.sendMessage("§6[Kernel Guardian] §7and update your recommended journeys.");
            player.sendMessage("§6[Kernel Guardian] §eType §a1 §eto reattempt, or §c2 §eto go back.");
        } else {
            player.sendMessage("§6[Kernel Guardian] §eWelcome to the Assessment Room.");
            player.sendMessage("§6[Kernel Guardian] §7I have prepared §e28 questions §7across all 7 journeys.");
            player.sendMessage("§6[Kernel Guardian] §7Your answers will help me recommend the best path for you.");
            player.sendMessage("§6[Kernel Guardian] §eType §a1 §eto begin, or §c2 §eto go back.");
        }
        player.sendMessage(configManager.getMessage("ui.separator"));
        awaitingConfirm.add(player.getUniqueId());
    }

    /** Cancels a pending quiz confirmation prompt (e.g. when player leaves the room). */
    public void cancelQuizConfirmation(Player player) {
        awaitingConfirm.remove(player.getUniqueId());
    }

    /** Drops the quiz session and any pending confirmation for this player (e.g. on quit). */
    public void dropSession(Player player) {
        UUID uuid = player.getUniqueId();
        activeSessions.remove(uuid);
        awaitingConfirm.remove(uuid);
    }

    /**
     * Called when the player leaves the Assessment Room mid-quiz.
     * Drops the active session and resets any partial quiz results so the
     * quiz starts fresh on the next visit.
     */
    public void abandonQuiz(Player player) {
        UUID uuid = player.getUniqueId();
        if (activeSessions.containsKey(uuid)) {
            activeSessions.remove(uuid);
            awaitingConfirm.remove(uuid);
            journeyTracker.resetQuiz(player);
        }
    }

    // ── Chat listener ─────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();

        // ── Quiz start confirmation (1 = yes, 2 = no) ────────────────────────
        if (awaitingConfirm.contains(player.getUniqueId())) {
            event.setCancelled(true);
            String input = PlainTextComponentSerializer.plainText()
                .serialize(event.message()).trim();
            if ("1".equals(input)) {
                awaitingConfirm.remove(player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> startQuiz(player));
            } else if ("2".equals(input)) {
                awaitingConfirm.remove(player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage(configManager.getMessage("guardian.quiz_cancelled"));
                    boolean quizDone = journeyTracker.hasCompletedQuiz(player);
                    journeyTracker.setPhase(player, quizDone ? "terminal_path_select" : "terminal_spawn");
                });
            } else {
                Bukkit.getScheduler().runTask(plugin, () ->
                    player.sendMessage(configManager.getMessage("errors.quiz.type_1_or_2")));
            }
            return;
        }

        // ── Active quiz session (A/B/C/D via chat fallback) ──────────────────
        QuizSession session = activeSessions.get(player.getUniqueId());
        if (session == null) return;

        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText()
            .serialize(event.message()).trim().toUpperCase();

        if (!input.equals("A") && !input.equals("B")
                && !input.equals("C") && !input.equals("D")) {
            Bukkit.getScheduler().runTask(plugin, () ->
                player.sendMessage(configManager.getMessage("errors.quiz.type_abcd")));
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> handleAnswer(player, session, input));
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void handleAnswer(Player player, QuizSession session, String input) {
        QuizQuestion qq = session.current();
        boolean correct = qq.question.checkAnswer(input);

        journeyTracker.recordQuizAnswer(
            player, qq.journey, qq.question.text, input, qq.question.correctAnswer);

        if (correct) {
            player.sendMessage(configManager.getMessage("feedback.correct"));
        } else {
            player.sendMessage("§c✘ " + qq.question.wrongFeedback);
            player.sendMessage(configManager.getMessage("feedback.correct_answer", "{answer}", qq.question.correctAnswer));
        }

        if (session.hasNext()) {
            session.advance();
            Bukkit.getScheduler().runTaskLater(plugin, () -> askQuestion(player, session), 15L);
        } else {
            activeSessions.remove(player.getUniqueId());
            Bukkit.getScheduler().runTaskLater(plugin, () -> showResults(player), 20L);
        }
    }

    private void askQuestion(Player player, QuizSession session) {
        QuizQuestion qq = session.current();
        int num   = session.index + 1;
        int total = session.questions.size();

        player.sendMessage(configManager.getMessage("ui.separator"));
        player.sendMessage("§eQuestion §6" + num + " §8/ §7" + total
            + "   §8[§6" + qq.journey.displayName + "§8]");
        player.sendMessage("§f" + qq.question.text);
        player.sendMessage(qq.question.formatOptions(null));
        player.sendMessage(configManager.getMessage("ui.quiz.question_prompt"));
    }

    private void showResults(Player player) {
        List<JourneyTracker.QuizResult> results    = journeyTracker.getQuizResults(player);
        Map<Journey, Integer>           wrongCounts = journeyTracker.getQuizWrongCounts(player);

        // Group by journey
        Map<Journey, List<JourneyTracker.QuizResult>> byJourney = new EnumMap<>(Journey.class);
        for (JourneyTracker.QuizResult r : results) {
            byJourney.computeIfAbsent(r.journey, k -> new ArrayList<>()).add(r);
        }

        player.sendMessage(configManager.getMessage("ui.separator"));
        player.sendMessage(configManager.getMessage("ui.quiz.results_title"));
        player.sendMessage(configManager.getMessage("ui.separator"));

        for (Journey j : Journey.values()) {
            List<JourneyTracker.QuizResult> jResults =
                byJourney.getOrDefault(j, new ArrayList<>());
            int wrong = wrongCounts.getOrDefault(j, 0);
            String wrongStr = wrong == 0 ? "§a0 wrong" : "§c" + wrong + " wrong";
            player.sendMessage("§e" + j.displayName + " §8(" + wrongStr + "§8)");
            for (JourneyTracker.QuizResult r : jResults) {
                String icon   = r.correct ? "§a✔" : "§c✘";
                String shortQ = r.question.length() > 55
                    ? r.question.substring(0, 52) + "..." : r.question;
                player.sendMessage("  " + icon + " §7" + shortQ);
            }
        }

        player.sendMessage(configManager.getMessage("ui.separator"));

        // Recommendation
        List<Journey> recommended = journeyTracker.getRecommendedJourneys(player);
        if (recommended.isEmpty()) {
            player.sendMessage(configManager.getMessage("ui.quiz.all_correct"));
            player.sendMessage(configManager.getMessage("ui.quiz.all_correct_note1"));
            player.sendMessage(configManager.getMessage("ui.quiz.all_correct_note2"));
        } else {
            player.sendMessage("§6§lRECOMMENDED JOURNEY"
                + (recommended.size() > 1 ? "S" : "") + ":");
            for (Journey j : recommended) {
                player.sendMessage("  §e▶ §f" + j.displayName
                    + " §8(§c" + wrongCounts.getOrDefault(j, 0) + " wrong§8)");
            }
            player.sendMessage(configManager.getMessage("ui.quiz.recommended_footer"));
        }

        player.sendMessage(configManager.getMessage("ui.separator"));
        player.sendMessage(configManager.getMessage("ui.quiz.footer"));

        // Mark quiz complete so terminal shows path selection on next visit
        journeyTracker.setPhase(player, "terminal_path_select");
    }

    private List<QuizQuestion> buildShuffledQuestions() {
        List<QuizQuestion> all = new ArrayList<>();
        for (Journey j : Journey.values()) {
            String section = JOURNEY_SECTION.get(j);
            if (section == null) continue;
            List<QuizQuestion> block = new ArrayList<>();
            for (String qKey : Q_KEYS) {
                String path = "assessment_quiz." + section + "." + qKey;
                QuestionBank.Question q = questionBank.getQuestion(path);
                if (q != null) block.add(new QuizQuestion(j, q));
            }
            Collections.shuffle(block);
            all.addAll(block);
        }
        Collections.shuffle(all);
        return all;
    }

    private String getCurrentRoom(Player player) {
        RoomRegistry.Room room = roomRegistry.getRoomAt(player.getLocation());
        return room != null ? room.title : "UNKNOWN";
    }
}
