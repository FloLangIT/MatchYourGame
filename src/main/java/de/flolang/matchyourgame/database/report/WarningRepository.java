package de.flolang.matchyourgame.database.report;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class WarningRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(WarningRepository.class);
    private WarningRepository() {}

    public static void init() {
        try (Connection conn = Database.getConnection(); Statement statement = conn.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS user_warning (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,reason TEXT NOT NULL," +
                    "moderator_user_id BIGINT NOT NULL,created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY(user_id) REFERENCES user(id),FOREIGN KEY(moderator_user_id) REFERENCES user(id))");
        } catch (SQLException e) { LOGGER.error("Could not initialize user warnings", e); }
    }

    public static Warning create(int userId, int moderatorUserId, String reason) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO user_warning(user_id,moderator_user_id,reason) VALUES (?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, userId); ps.setInt(2, moderatorUserId); ps.setString(3, reason); ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? new Warning(keys.getInt(1), userId, moderatorUserId, reason,
                        new Timestamp(System.currentTimeMillis())) : null;
            }
        } catch (SQLException e) { LOGGER.error("Could not warn user {}", userId, e); return null; }
    }

    public static List<Warning> forUser(int userId) {
        List<Warning> warnings = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT id,user_id,moderator_user_id,reason,created_at FROM user_warning WHERE user_id=? ORDER BY created_at DESC")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) warnings.add(new Warning(rs.getInt(1), rs.getInt(2), rs.getInt(3),
                        rs.getString(4), rs.getTimestamp(5)));
            }
        } catch (SQLException e) { LOGGER.error("Could not list warnings for user {}", userId, e); }
        return warnings;
    }

    public record Warning(int id, int userId, int moderatorUserId, String reason, Timestamp createdAt) {}
}
