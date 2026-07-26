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
                    "PRIMARY KEY (user_id,language_code)," +
                    "FOREIGN KEY (user_id) REFERENCES user(id))");
            boolean legacyPriorityIndex;
            try (ResultSet indexes = statement.executeQuery(
                    "SHOW INDEX FROM user_communication_language WHERE Key_name='uq_user_language_priority'")) {
                legacyPriorityIndex = indexes.next();
            }
            if (legacyPriorityIndex)
                statement.executeUpdate("ALTER TABLE user_communication_language " +
                        "DROP INDEX uq_user_language_priority");
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
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO user_communication_language (user_id,language_code,priority) VALUES (?,?,?) " +
                        "ON DUPLICATE KEY UPDATE priority=VALUES(priority)")) {
            ps.setInt(1, userId);
            ps.setString(2, normalized);
            ps.setInt(3, priority);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) { LOGGER.error("Could not save communication language", e); return false; }
    }

    public static boolean delete(int userId, String language) {
        if (language == null || language.isBlank()) return false;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement count = conn.prepareStatement(
                    "SELECT language_code FROM user_communication_language WHERE user_id=? FOR UPDATE");
                 PreparedStatement delete = conn.prepareStatement(
                         "DELETE FROM user_communication_language WHERE user_id=? AND language_code=?")) {
                count.setInt(1, userId);
                try (ResultSet rs = count.executeQuery()) {
                    int configured = 0;
                    while (rs.next()) configured++;
                    if (configured <= 1) {
                        conn.rollback();
                        return false;
                    }
                }
                delete.setInt(1, userId);
                delete.setString(2, language.trim().toUpperCase());
                boolean deleted = delete.executeUpdate() == 1;
                conn.commit();
                return deleted;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOGGER.error("Could not delete communication language", e);
            return false;
        }
    }

    public static boolean addAtEnd(int userId, String language) {
        int priority = getForUser(userId).stream().mapToInt(CommunicationLanguage::priority).max().orElse(0) + 1;
        return upsert(userId, language, priority);
    }
}
