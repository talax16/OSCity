package com.oscity.persistence;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * SQLite-specific implementation for user study data tracking.
 * Extends StudyDataLogger with SQLite utilities and analysis features.
 * 
 * Database location: study_data.db (created automatically in project root)
 */
public class SQLiteStudyDatabase {
    private static final String DB_PATH = "jdbc:sqlite:study_data.db";
    private static final String DB_FILE = "study_data.db";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Initialize SQLite database with all necessary tables
     */
    public static void initializeDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_PATH)) {
            Statement stmt = conn.createStatement();

            // Sessions table - track basic session info
            stmt.execute("CREATE TABLE IF NOT EXISTS sessions (" +
                    "session_id TEXT PRIMARY KEY," +
                    "mode TEXT NOT NULL," +
                    "start_time DATETIME NOT NULL," +
                    "end_time DATETIME," +
                    "duration_seconds INTEGER," +
                    "created_at DATETIME DEFAULT CURRENT_TIMESTAMP" +
                    ")");

            // Achievements table
            stmt.execute("CREATE TABLE IF NOT EXISTS study_achievements (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "session_id TEXT NOT NULL," +
                    "achievement_name TEXT NOT NULL," +
                    "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY(session_id) REFERENCES sessions(session_id)" +
                    ")");

            // Interactions table (hints and wrong answers)
            stmt.execute("CREATE TABLE IF NOT EXISTS study_interactions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "session_id TEXT NOT NULL," +
                    "event_type TEXT NOT NULL," +  // HINT_USED or WRONG_ANSWER
                    "room TEXT," +
                    "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY(session_id) REFERENCES sessions(session_id)" +
                    ")");

            // Create indexes for faster queries
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_achievements_session " +
                    "ON study_achievements(session_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_interactions_session " +
                    "ON study_interactions(session_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_interactions_type " +
                    "ON study_interactions(event_type)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_interactions_room " +
                    "ON study_interactions(room)");

            stmt.close();
            System.out.println("[StudyDB] Database initialized successfully at: " + DB_FILE);
        } catch (SQLException e) {
            System.err.println("[StudyDB] Failed to initialize database: " + e.getMessage());
        }
    }

    /**
     * Record a new session start
     */
    public static void startSession(String sessionId, String mode, LocalDateTime startTime) {
        String sql = "INSERT INTO sessions (session_id, mode, start_time) VALUES (?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(DB_PATH);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            pstmt.setString(2, mode);
            pstmt.setString(3, startTime.format(TIMESTAMP_FORMAT));
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[StudyDB] Failed to start session: " + e.getMessage());
        }
    }

    /**
     * Record session end and calculate duration
     */
    public static void endSession(String sessionId, LocalDateTime endTime) {
        String sql = "UPDATE sessions SET end_time = ?, duration_seconds = " +
                "CAST((julianday(?) - julianday(start_time)) * 86400 AS INTEGER) " +
                "WHERE session_id = ?";
        try (Connection conn = DriverManager.getConnection(DB_PATH);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, endTime.format(TIMESTAMP_FORMAT));
            pstmt.setString(2, endTime.format(TIMESTAMP_FORMAT));
            pstmt.setString(3, sessionId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[StudyDB] Failed to end session: " + e.getMessage());
        }
    }

    /**
     * Log an achievement unlock
     */
    public static void logAchievement(String sessionId, String achievementName) {
        String sql = "INSERT INTO study_achievements (session_id, achievement_name) VALUES (?, ?)";
        try (Connection conn = DriverManager.getConnection(DB_PATH);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            pstmt.setString(2, achievementName);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[StudyDB] Failed to log achievement: " + e.getMessage());
        }
    }

    /**
     * Log a hint usage
     */
    public static void logHintUsed(String sessionId, String room) {
        String sql = "INSERT INTO study_interactions (session_id, event_type, room) VALUES (?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(DB_PATH);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            pstmt.setString(2, "HINT_USED");
            pstmt.setString(3, room);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[StudyDB] Failed to log hint: " + e.getMessage());
        }
    }

    /**
     * Log a wrong answer
     */
    public static void logWrongAnswer(String sessionId, String room) {
        String sql = "INSERT INTO study_interactions (session_id, event_type, room) VALUES (?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(DB_PATH);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            pstmt.setString(2, "WRONG_ANSWER");
            pstmt.setString(3, room);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[StudyDB] Failed to log wrong answer: " + e.getMessage());
        }
    }

    /**
     * Get summary statistics for all sessions
     */
    public static void printStudySummary() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("USER STUDY SUMMARY REPORT");
        System.out.println("=".repeat(80));

        try (Connection conn = DriverManager.getConnection(DB_PATH)) {
            // Count sessions
            System.out.println("\n[SESSIONS]");
            String sessionSQL = "SELECT COUNT(*) as total, " +
                    "SUM(CASE WHEN mode = 'LEARNER' THEN 1 ELSE 0 END) as learner_mode, " +
                    "SUM(CASE WHEN mode = 'ADVENTURER' THEN 1 ELSE 0 END) as adventurer_mode, " +
                    "ROUND(AVG(duration_seconds)/60.0, 1) as avg_duration_minutes " +
                    "FROM sessions WHERE end_time IS NOT NULL";

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sessionSQL)) {
                if (rs.next()) {
                    System.out.println("  Total Completed Sessions: " + rs.getInt("total"));
                    System.out.println("  Learner Mode: " + rs.getInt("learner_mode"));
                    System.out.println("  Adventurer Mode: " + rs.getInt("adventurer_mode"));
                    System.out.println("  Average Duration: " + rs.getDouble("avg_duration_minutes") + " minutes");
                }
            }

            // Achievements summary
            System.out.println("\n[ACHIEVEMENTS]");
            String achieveSQL = "SELECT COUNT(DISTINCT session_id) as sessions_with_achievements, " +
                    "COUNT(*) as total_achievements, " +
                    "COUNT(DISTINCT achievement_name) as unique_achievements " +
                    "FROM study_achievements";

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(achieveSQL)) {
                if (rs.next()) {
                    System.out.println("  Sessions With Achievements: " + rs.getInt("sessions_with_achievements"));
                    System.out.println("  Total Achievements Unlocked: " + rs.getInt("total_achievements"));
                    System.out.println("  Unique Achievements: " + rs.getInt("unique_achievements"));
                }
            }

            // Interactions summary
            System.out.println("\n[PLAYER INTERACTIONS]");
            String interactSQL = "SELECT " +
                    "SUM(CASE WHEN event_type = 'HINT_USED' THEN 1 ELSE 0 END) as hints_used, " +
                    "SUM(CASE WHEN event_type = 'WRONG_ANSWER' THEN 1 ELSE 0 END) as wrong_answers, " +
                    "COUNT(*) as total_interactions " +
                    "FROM study_interactions";

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(interactSQL)) {
                if (rs.next()) {
                    System.out.println("  Total Hints Used: " + rs.getInt("hints_used"));
                    System.out.println("  Total Wrong Answers: " + rs.getInt("wrong_answers"));
                    System.out.println("  Total Interactions: " + rs.getInt("total_interactions"));
                }
            }

            // Per-room analysis
            System.out.println("\n[PROBLEMATIC ROOMS - Where players struggled most]");
            String roomSQL = "SELECT room, " +
                    "SUM(CASE WHEN event_type = 'HINT_USED' THEN 1 ELSE 0 END) as hints, " +
                    "SUM(CASE WHEN event_type = 'WRONG_ANSWER' THEN 1 ELSE 0 END) as wrongs, " +
                    "COUNT(*) as total " +
                    "FROM study_interactions " +
                    "WHERE room IS NOT NULL " +
                    "GROUP BY room " +
                    "ORDER BY total DESC";

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(roomSQL)) {
                if (!rs.isBeforeFirst()) {
                    System.out.println("  (No interaction data yet)");
                } else {
                    System.out.printf("  %-30s | %6s | %6s | %6s%n", "Room", "Hints", "Wrongs", "Total");
                    System.out.println("  " + "-".repeat(60));
                    while (rs.next()) {
                        System.out.printf("  %-30s | %6d | %6d | %6d%n",
                                rs.getString("room"),
                                rs.getInt("hints"),
                                rs.getInt("wrongs"),
                                rs.getInt("total"));
                    }
                }
            }

            // Top achievements
            System.out.println("\n[TOP ACHIEVEMENTS]");
            String topSQL = "SELECT achievement_name, COUNT(*) as unlocked_by " +
                    "FROM study_achievements " +
                    "GROUP BY achievement_name " +
                    "ORDER BY unlocked_by DESC " +
                    "LIMIT 10";

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(topSQL)) {
                if (!rs.isBeforeFirst()) {
                    System.out.println("  (No achievement data yet)");
                } else {
                    System.out.printf("  %-40s | %6s%n", "Achievement", "Players");
                    System.out.println("  " + "-".repeat(50));
                    while (rs.next()) {
                        System.out.printf("  %-40s | %6d%n",
                                rs.getString("achievement_name"),
                                rs.getInt("unlocked_by"));
                    }
                }
            }

            System.out.println("\n" + "=".repeat(80) + "\n");
        } catch (SQLException e) {
            System.err.println("[StudyDB] Failed to generate summary: " + e.getMessage());
        }
    }

    /**
     * Export all data to CSV for analysis in spreadsheet
     */
    public static void exportToCSV(String outputFolder) {
        System.out.println("[StudyDB] Exporting data to CSV files...");
        
        try (Connection conn = DriverManager.getConnection(DB_PATH)) {
            // Export sessions
            exportTableToCSV(conn, "sessions", outputFolder + "/sessions.csv");
            exportTableToCSV(conn, "study_achievements", outputFolder + "/achievements.csv");
            exportTableToCSV(conn, "study_interactions", outputFolder + "/interactions.csv");
            
            System.out.println("[StudyDB] CSV export complete. Files saved to: " + outputFolder);
        } catch (SQLException e) {
            System.err.println("[StudyDB] Failed to export data: " + e.getMessage());
        }
    }

    /**
     * Helper: Export a table to CSV
     */
    private static void exportTableToCSV(Connection conn, String tableName, String csvFile) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + tableName)) {

            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(
                csvFile.substring(0, csvFile.lastIndexOf('/'))));

            try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(csvFile))) {
                // Write headers
                ResultSetMetaData rsmd = rs.getMetaData();
                int columnCount = rsmd.getColumnCount();
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) pw.print(",");
                    pw.print(rsmd.getColumnName(i));
                }
                pw.println();

                // Write data
                while (rs.next()) {
                    for (int i = 1; i <= columnCount; i++) {
                        if (i > 1) pw.print(",");
                        Object value = rs.getObject(i);
                        if (value != null) {
                            pw.print("\"" + value.toString().replace("\"", "\"\"") + "\"");
                        }
                    }
                    pw.println();
                }
            }
        } catch (Exception e) {
            System.err.println("[StudyDB] Failed to export " + tableName + ": " + e.getMessage());
        }
    }

    /**
     * Delete all study data (use at start of new study session)
     */
    public static void clearDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_PATH)) {
            Statement stmt = conn.createStatement();
            stmt.execute("DELETE FROM study_achievements");
            stmt.execute("DELETE FROM study_interactions");
            stmt.execute("DELETE FROM sessions");
            stmt.close();
            System.out.println("[StudyDB] Database cleared successfully");
        } catch (SQLException e) {
            System.err.println("[StudyDB] Failed to clear database: " + e.getMessage());
        }
    }

    /**
     * Test the database connection
     */
    public static void testConnection() {
        try (Connection conn = DriverManager.getConnection(DB_PATH)) {
            if (conn != null) {
                System.out.println("[StudyDB] ✓ SQLite connection successful");
                System.out.println("[StudyDB] Database file: " + DB_FILE);
            }
        } catch (SQLException e) {
            System.err.println("[StudyDB] ✗ Connection failed: " + e.getMessage());
        }
    }
}