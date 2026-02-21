package com.oscity.persistence;

import com.oscity.journey.Journey;

import java.sql.*;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Persists which journeys each player has completed to the SQLite database.
 *
 * Table: journey_completions
 *   player_uuid   TEXT  — player UUID string
 *   journey_number INT  — Journey.number (1–7)
 *   completed_at  DATETIME DEFAULT CURRENT_TIMESTAMP
 *   PRIMARY KEY (player_uuid, journey_number)  — prevents duplicates
 */
public class JourneyDAO {

    private static final String DB_PATH = "jdbc:sqlite:study_data.db";

    // ── Called once at startup (from SQLiteStudyDatabase.initializeDatabase) ─

    public static void ensureTable() {
        String sql = "CREATE TABLE IF NOT EXISTS journey_completions (" +
                "player_uuid TEXT NOT NULL," +
                "journey_number INTEGER NOT NULL," +
                "completed_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                "PRIMARY KEY (player_uuid, journey_number)" +
                ")";
        try (Connection conn = DriverManager.getConnection(DB_PATH);
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            System.err.println("[JourneyDAO] Failed to create table: " + e.getMessage());
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Record a journey as completed for this player.
     * INSERT OR IGNORE ensures no error if already present.
     */
    public static void markComplete(UUID playerUuid, Journey journey) {
        String sql = "INSERT OR IGNORE INTO journey_completions (player_uuid, journey_number) VALUES (?, ?)";
        try (Connection conn = DriverManager.getConnection(DB_PATH);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, playerUuid.toString());
            pstmt.setInt(2, journey.number);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[JourneyDAO] Failed to mark complete: " + e.getMessage());
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Load all journeys this player has completed.
     * Returns an empty set if the player has no data yet.
     */
    public static Set<Journey> loadCompleted(UUID playerUuid) {
        Set<Journey> result = EnumSet.noneOf(Journey.class);
        String sql = "SELECT journey_number FROM journey_completions WHERE player_uuid = ?";
        try (Connection conn = DriverManager.getConnection(DB_PATH);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, playerUuid.toString());
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                Journey j = Journey.fromNumber(rs.getInt("journey_number"));
                if (j != null) result.add(j);
            }
        } catch (SQLException e) {
            System.err.println("[JourneyDAO] Failed to load completed: " + e.getMessage());
        }
        return result;
    }
}
