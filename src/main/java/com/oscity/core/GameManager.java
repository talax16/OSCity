package com.oscity.core;

import com.oscity.session.SessionManager;
import com.oscity.persistence.SQLiteStudyDatabase;

public class GameManager {

    private SessionManager sessionManager;

    public GameManager() {
        this.sessionManager = new SessionManager();
    }

    /**
     * Called when player selects LEARNER mode at terminal
     */
    public void startLearnerMode() {
        sessionManager.startSession("LEARNER");
        SQLiteStudyDatabase.startSession(
            sessionManager.getSessionId(),
            "LEARNER",
            sessionManager.getStartTime()
        );
    }

    /**
     * Called when player selects ADVENTURER mode at terminal
     */
    public void startAdventurerMode() {
        sessionManager.startSession("ADVENTURER");
        SQLiteStudyDatabase.startSession(
            sessionManager.getSessionId(),
            "ADVENTURER",
            sessionManager.getStartTime()
        );
    }

    /**
     * Called when player reaches finish terminal
     */
    public void playerFinished() {
        sessionManager.endSession();
        SQLiteStudyDatabase.endSession(
            sessionManager.getSessionId(),
            sessionManager.getEndTime()
        );
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }
}
