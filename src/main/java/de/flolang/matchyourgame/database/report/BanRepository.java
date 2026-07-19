package de.flolang.matchyourgame.database.report;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;

public final class BanRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(BanRepository.class);
    private BanRepository() {}

    public static void init() {
        try (Connection conn = Database.getConnection(); Statement statement = conn.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS myg_ban (user_id BIGINT PRIMARY KEY," +
                    "report_id BIGINT NULL,expires_at TIMESTAMP NULL,reason TEXT NOT NULL," +
                    "moderator_discord_id VARCHAR(32) NOT NULL,banned_discord_id VARCHAR(32) NULL," +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY(user_id) REFERENCES user(id),FOREIGN KEY(report_id) REFERENCES myg_report(id))");
            try (ResultSet columns = conn.getMetaData().getColumns(conn.getCatalog(), null, "myg_ban", "banned_discord_id")) {
                if (!columns.next()) statement.executeUpdate("ALTER TABLE myg_ban ADD COLUMN banned_discord_id VARCHAR(32) NULL");
            }
        } catch (SQLException e) { LOGGER.error("Could not initialize bans", e); }
    }

    public static boolean ban(int userId, int reportId, Instant expiresAt, String reason, long moderatorId) {
        String sql = "INSERT INTO myg_ban(user_id,report_id,expires_at,reason,moderator_discord_id,banned_discord_id) " +
                "SELECT ?,?,?,?,?,discord_id FROM user WHERE id=? " +
                "ON DUPLICATE KEY UPDATE report_id=VALUES(report_id),expires_at=VALUES(expires_at)," +
                "reason=VALUES(reason),moderator_discord_id=VALUES(moderator_discord_id)," +
                "banned_discord_id=VALUES(banned_discord_id),created_at=CURRENT_TIMESTAMP";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            if (reportId <= 0) ps.setNull(2, Types.BIGINT); else ps.setInt(2, reportId);
            if (expiresAt == null) ps.setNull(3, Types.TIMESTAMP); else ps.setTimestamp(3, Timestamp.from(expiresAt));
            ps.setString(4, reason); ps.setString(5, String.valueOf(moderatorId)); ps.setInt(6, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) { LOGGER.error("Could not ban user {}", userId, e); return false; }
    }

    public static BanInfo getActive(int userId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT expires_at,reason,moderator_discord_id,created_at FROM myg_ban " +
                        "WHERE user_id=? AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new BanInfo(rs.getTimestamp("expires_at"), rs.getString("reason"),
                        rs.getString("moderator_discord_id"), rs.getTimestamp("created_at")) : null;
            }
        } catch (SQLException e) { LOGGER.error("Could not load ban for user {}", userId, e); return null; }
    }

    public record BanInfo(Timestamp expiresAt, String reason, String moderatorDiscordId, Timestamp createdAt) {}

    public static boolean isBanned(int userId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM myg_ban WHERE user_id=? AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)")) {
            ps.setInt(1, userId); try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { LOGGER.error("Could not check ban for user {}", userId, e); return false; }
    }

    public static boolean isDiscordBanned(long discordId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM myg_ban WHERE banned_discord_id=? AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)")) {
            ps.setString(1, String.valueOf(discordId));
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { LOGGER.error("Could not check Discord ban {}", discordId, e); return false; }
    }

    public static boolean unban(int userId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE myg_ban SET expires_at=CURRENT_TIMESTAMP WHERE user_id=? AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)")) {
            ps.setInt(1, userId); return ps.executeUpdate() == 1;
        } catch (SQLException e) { LOGGER.error("Could not unban user {}", userId, e); return false; }
    }
}
