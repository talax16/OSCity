package com.oscity.quiz;

import com.oscity.content.QuestionBank;
import com.oscity.persistence.SQLiteStudyDatabase;
import com.oscity.session.SessionManager;
import com.oscity.world.RoomRegistry;
import org.bukkit.entity.Player;

/**
 * Validates quiz answers submitted by the player.
 * Questions are loaded from questions.yml via QuestionBank.
 * Wrong answers are logged to the SQLite study database.
 */
public class QuizManager {

    private final SessionManager sessionManager;
    private final RoomRegistry roomRegistry;
    private final QuestionBank questionBank;

    public QuizManager(SessionManager sessionManager, RoomRegistry roomRegistry,
                       QuestionBank questionBank) {
        this.sessionManager = sessionManager;
        this.roomRegistry = roomRegistry;
        this.questionBank = questionBank;
    }

    /**
     * Validate a player's answer for a given question path (e.g. "tlb_room.miss_door").
     * Returns true if the answer is correct. Logs wrong answers to the database.
     */
    public boolean validateAnswer(Player player, String questionPath, String playerAnswer) {
        QuestionBank.Question question = questionBank.getQuestion(questionPath);
        if (question == null) return false;

        boolean isCorrect = question.checkAnswer(playerAnswer);

        if (isCorrect) {
            player.sendMessage("§a§lCorrect!");
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

    private String getCurrentRoom(Player player) {
        RoomRegistry.Room room = roomRegistry.getRoomAt(player.getLocation());
        return room != null ? room.title : "UNKNOWN";
    }
}
