package com.oscity.quiz;

import com.oscity.session.SessionManager;
import com.oscity.persistence.SQLiteStudyDatabase;
import com.oscity.world.RoomRegistry;
import org.bukkit.entity.Player;

public class QuizManager {

    private SessionManager sessionManager;
    private RoomRegistry roomRegistry;

    public QuizManager(SessionManager sessionManager, RoomRegistry roomRegistry) {
        this.sessionManager = sessionManager;
        this.roomRegistry = roomRegistry;
    }

    /**
     * Called when player submits an answer.
     * Returns true if the answer is correct.
     */
    public boolean validateAnswer(Player player, String question, String playerAnswer) {
        String correctAnswer = getCorrectAnswer(question);
        boolean isCorrect = playerAnswer.equalsIgnoreCase(correctAnswer);

        if (isCorrect) {
            player.sendMessage("Correct!");
        } else {
            player.sendMessage("Incorrect. Try again.");
            sessionManager.recordWrongAnswer();
            SQLiteStudyDatabase.logWrongAnswer(
                sessionManager.getSessionId(),
                getCurrentRoom(player)
            );
        }

        return isCorrect;
    }

    private String getCorrectAnswer(String question) {
        // Return correct answer for this question
        return "answer";
    }

    private String getCurrentRoom(Player player) {
        RoomRegistry.Room room = roomRegistry.getRoomAt(player.getLocation());
        return room != null ? room.title : "UNKNOWN";
    }
}
