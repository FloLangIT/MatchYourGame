package de.flolang.matchyourgame.database.profile;

import de.flolang.matchyourgame.database.Database;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class CommunicationLanguageRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommunicationLanguageRepository.class);
    private CommunicationLanguageRepository() {}

    public static void init() {
        try (Connection conn = Database.getConnection(); Statement statement = conn.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS user_communication_language (" +
                    "user_id BIGINT NOT NULL,language_code VARCHAR(20) NOT NULL,priority INT NOT NULL DEFAULT 1," +
                    "PRIMARY KEY (user_id,language_code),UNIQUE KEY uq_user_language_priority (user_id,priority)," +
                    "FOREIGN KEY (user_id) REFERENCES user(id))");
        } catch (SQLException e) { LOGGER.error("Could not initialize communication languages", e); }
    }

    public static List<CommunicationLanguage> getForUser(int userId) {
        List<CommunicationLanguage> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT language_code,priority FROM user_communication_language WHERE user_id=? ORDER BY priority,language_code")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new CommunicationLanguage(rs.getString(1), rs.getInt(2)));
            }
        } catch (SQLException e) { LOGGER.error("Could not load communication languages", e); }
        if (result.isEmpty()) {
            UserObject user = UserController.get(userId);
            if (user != null && upsert(userId, user.getLanguage().name(), 1))
                return List.of(new CommunicationLanguage(user.getLanguage().name(), 1));
        }
        return result;
    }

    public static boolean upsert(int userId, String language, int priority) {
        if (language == null || language.isBlank() || priority < 1) return false;
        String normalized = language.trim().toUpperCase();
        if (normalized.length() > 20) return false;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT IGNORE INTO user_communication_language (user_id,language_code,priority) " +
                            "SELECT ?,?,COALESCE(MAX(priority),0)+1 FROM user_communication_language WHERE user_id=?")) {
                insert.setInt(1, userId); insert.setString(2, normalized); insert.setInt(3, userId);
                insert.executeUpdate();

                List<String> ordered = new ArrayList<>();
                try (PreparedStatement select = conn.prepareStatement(
                        "SELECT language_code FROM user_communication_language WHERE user_id=? ORDER BY priority,language_code")) {
                    select.setInt(1, userId);
                    try (ResultSet rs = select.executeQuery()) { while (rs.next()) ordered.add(rs.getString(1)); }
                }
                ordered.removeIf(normalized::equalsIgnoreCase);
                ordered.add(Math.min(priority - 1, ordered.size()), normalized);

                try (PreparedStatement moveAside = conn.prepareStatement(
                        "UPDATE user_communication_language SET priority=-priority WHERE user_id=?");
                     PreparedStatement reorder = conn.prepareStatement(
                             "UPDATE user_communication_language SET priority=? WHERE user_id=? AND language_code=?")) {
                    moveAside.setInt(1, userId); moveAside.executeUpdate();
                    for (int index = 0; index < ordered.size(); index++) {
                        reorder.setInt(1, index + 1); reorder.setInt(2, userId);
                        reorder.setString(3, ordered.get(index)); reorder.addBatch();
                    }
                    reorder.executeBatch();
                }
                conn.commit(); return true;
            } catch (SQLException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { LOGGER.error("Could not save communication language", e); return false; }
    }

    public static boolean addAtEnd(int userId, String language) {
        int priority = getForUser(userId).stream().mapToInt(CommunicationLanguage::priority).max().orElse(0) + 1;
        return upsert(userId, language, priority);
    }
}
