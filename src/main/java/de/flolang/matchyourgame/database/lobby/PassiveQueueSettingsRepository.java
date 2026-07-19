package de.flolang.matchyourgame.database.lobby;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

public final class PassiveQueueSettingsRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(PassiveQueueSettingsRepository.class);
    private PassiveQueueSettingsRepository() {}

    public static void init() {
        try (Connection conn = Database.getConnection(); Statement statement = conn.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS passive_queue_settings (" +
                    "user_id BIGINT PRIMARY KEY," +
                    "enabled BOOLEAN NOT NULL DEFAULT TRUE," +
                    "sync_online_status BOOLEAN NOT NULL DEFAULT FALSE," +
                    "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                    "FOREIGN KEY (user_id) REFERENCES user(id))");
        } catch (SQLException e) { LOGGER.error("Could not initialize PassiveQ settings", e); }
    }

    public static PassiveQueueSettings get(int userId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM passive_queue_settings WHERE user_id=?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new PassiveQueueSettings(userId, rs.getBoolean("enabled"), rs.getBoolean("sync_online_status"));
            }
        } catch (SQLException e) { LOGGER.error("Could not load PassiveQ settings", e); }
        return new PassiveQueueSettings(userId, true, false);
    }

    public static PassiveQueueSettings update(int userId, boolean enabled, boolean syncOnline) {
        String sql = "INSERT INTO passive_queue_settings (user_id,enabled,sync_online_status) VALUES (?,?,?) " +
                "ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),sync_online_status=VALUES(sync_online_status)";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId); ps.setBoolean(2, enabled); ps.setBoolean(3, syncOnline); ps.executeUpdate();
            return new PassiveQueueSettings(userId, enabled, syncOnline);
        } catch (SQLException e) { LOGGER.error("Could not update PassiveQ settings", e); return null; }
    }
}
