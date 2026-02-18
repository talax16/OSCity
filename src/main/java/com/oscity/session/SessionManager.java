package com.oscity.session;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Manages a single anonymous player session for user study tracking.
 * Does NOT persist session data - only tracks during active gameplay.
 * Session data is logged to StudyDataLogger for analysis.
 */
public class SessionManager {
    private String sessionId;          // Anonymous session ID (UUID)
    private String mode;               // "LEARNER" or "ADVENTURER"
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private boolean isActive;
    private int hintsUsed;
    private int wrongAnswers;

    public SessionManager() {
        this.sessionId = UUID.randomUUID().toString().substring(0, 8); // Short anonymous ID
        this.startTime = LocalDateTime.now();
        this.isActive = false;
        this.hintsUsed = 0;
        this.wrongAnswers = 0;
    }

    /**
     * Start a new session with chosen mode
     */
    public void startSession(String mode) {
        this.mode = mode;
        this.startTime = LocalDateTime.now();
        this.isActive = true;
    }

    /**
     * End the current session
     */
    public void endSession() {
        this.endTime = LocalDateTime.now();
        this.isActive = false;
    }

    /**
     * Record a hint was used by the player
     */
    public void recordHintUsed() {
        this.hintsUsed++;
    }

    /**
     * Record a wrong answer
     */
    public void recordWrongAnswer() {
        this.wrongAnswers++;
    }

    // Getters
    public String getSessionId() {
        return sessionId;
    }

    public String getMode() {
        return mode;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public boolean isActive() {
        return isActive;
    }

    public int getHintsUsed() {
        return hintsUsed;
    }

    public int getWrongAnswers() {
        return wrongAnswers;
    }

    public long getSessionDurationSeconds() {
        if (endTime == null) return -1;
        return java.time.temporal.ChronoUnit.SECONDS.between(startTime, endTime);
    }
}