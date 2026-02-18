package com.oscity.persistence;

import java.sql.*;
import java.time.LocalDateTime;

/**
 * Logs all user study data: achievements, hints used, wrong answers.
 * Single responsibility: append events to a study database.
 * Works with both SQLite and other JDBC-compatible databases.
 */
public class StudyDataLogger {
    private String dbUrl;
    private static final String ACHIEVEMENTS_TABLE = "study_achievements";
    private static final String INTERACTIONS_TABLE = "study_interactions";

    public StudyDataLogger(String dbPath) {
        // For SQLite: "jdbc:sqlite:study_data.db"
        // For MySQL: "jdbc:mysql://localhost:3306/oscity_study"
        this.dbUrl = dbPath;
        initializeDatabase();
    }

    /**
     * Initialize database tables if they don't exist
     */
    private void initializeDatabase() {
        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            String achievementsSQL = "CREATE TABLE IF NOT EXISTS " + ACHIEVEMENTS_TABLE + " (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "session_id TEXT NOT NULL," +
                    "achievement_name TEXT NOT NULL," +
                    "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP" +
                    ")";

            String interactionsSQL = "CREATE TABLE IF NOT EXISTS " + INTERACTIONS_TABLE + " (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "session_id TEXT NOT NULL," +
                    "event_type TEXT NOT NULL," +  // 'HINT_USED' or 'WRONG_ANSWER'
                    "room TEXT," +
                    "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP" +
                    ")";

            try (Statement stmt = conn.createStatement()) {
                stmt.execute(achievementsSQL);
                stmt.execute(interactionsSQL);
            }
        } catch (SQLException e) {
            System.err.println("Failed to initialize study database: " + e.getMessage());
        }
    }

    /**
     * Log an achievement unlock
     */
    public void logAchievement(String sessionId, String achievementName) {
        String sql = "INSERT INTO " + ACHIEVEMENTS_TABLE + " (session_id, achievement_name) VALUES (?, ?)";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            pstmt.setString(2, achievementName);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to log achievement: " + e.getMessage());
        }
    }

    /**
     * Log a hint usage
     */
    public void logHintUsed(String sessionId, String room) {
        logInteraction(sessionId, "HINT_USED", room);
    }

    /**
     * Log a wrong answer
     */
    public void logWrongAnswer(String sessionId, String room) {
        logInteraction(sessionId, "WRONG_ANSWER", room);
    }

    /**
     * Generic interaction logger
     */
    private void logInteraction(String sessionId, String eventType, String room) {
        String sql = "INSERT INTO " + INTERACTIONS_TABLE + " (session_id, event_type, room) VALUES (?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            pstmt.setString(2, eventType);
            pstmt.setString(3, room);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to log interaction: " + e.getMessage());
        }
    }

    /**
     * Get summary statistics for a session
     */
    public SessionSummary getSessionSummary(String sessionId) {
        SessionSummary summary = new SessionSummary();

        String achievementSQL = "SELECT COUNT(*) FROM " + ACHIEVEMENTS_TABLE + " WHERE session_id = ?";
        String hintSQL = "SELECT COUNT(*) FROM " + INTERACTIONS_TABLE + 
                         " WHERE session_id = ? AND event_type = 'HINT_USED'";
        String wrongSQL = "SELECT COUNT(*) FROM " + INTERACTIONS_TABLE + 
                          " WHERE session_id = ? AND event_type = 'WRONG_ANSWER'";

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            // Count achievements
            try (PreparedStatement pstmt = conn.prepareStatement(achievementSQL)) {
                pstmt.setString(1, sessionId);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) summary.achievementsUnlocked = rs.getInt(1);
            }

            // Count hints
            try (PreparedStatement pstmt = conn.prepareStatement(hintSQL)) {
                pstmt.setString(1, sessionId);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) summary.hintsUsed = rs.getInt(1);
            }

            // Count wrong answers
            try (PreparedStatement pstmt = conn.prepareStatement(wrongSQL)) {
                pstmt.setString(1, sessionId);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) summary.wrongAnswers = rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("Failed to retrieve session summary: " + e.getMessage());
        }

        return summary;
    }

    /**
     * Simple data class for session summaries
     */
    public static class SessionSummary {
        public int achievementsUnlocked;
        public int hintsUsed;
        public int wrongAnswers;

        @Override
        public String toString() {
            return String.format("Achievements: %d, Hints Used: %d, Wrong Answers: %d",
                    achievementsUnlocked, hintsUsed, wrongAnswers);
        }
    }
}