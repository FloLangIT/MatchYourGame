package de.flolang.matchyourgame.database.report;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

public final class ReportRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReportRepository.class);
    public enum Status { OPEN, RESOLVED, REJECTED, DELETED }
    public record Report(int id, String type, int reporterId, Integer targetUserId, String subject,
                         String details, Status status, long messageId, long contactChannelId,
                         long contactDmMessageId, String contactTranscript, boolean contactClosed) {}

    private ReportRepository() {}

    public static void init() {
        try (Connection conn = Database.getConnection(); Statement statement = conn.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS myg_report (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY,type VARCHAR(16) NOT NULL,reporter_id BIGINT NOT NULL," +
                    "target_user_id BIGINT NULL,subject VARCHAR(100) NOT NULL,details TEXT NOT NULL," +
                    "status VARCHAR(16) NOT NULL DEFAULT 'OPEN',message_id VARCHAR(32) NULL," +
                    "contact_channel_id VARCHAR(32) NULL,resolution_message TEXT NULL," +
                    "contact_dm_message_id VARCHAR(32) NULL,contact_transcript MEDIUMTEXT NULL," +
                    "contact_closed BOOLEAN NOT NULL DEFAULT FALSE," +
                    "moderator_discord_id VARCHAR(32) NULL,created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "resolved_at TIMESTAMP NULL,FOREIGN KEY (reporter_id) REFERENCES user(id)," +
                    "FOREIGN KEY (target_user_id) REFERENCES user(id))");
            addColumnIfMissing(conn, "contact_dm_message_id", "VARCHAR(32) NULL");
            addColumnIfMissing(conn, "contact_transcript", "MEDIUMTEXT NULL");
            addColumnIfMissing(conn, "contact_closed", "BOOLEAN NOT NULL DEFAULT FALSE");
        } catch (SQLException e) { LOGGER.error("Could not initialize reports", e); }
    }

    public static Report create(String type, int reporterId, Integer targetId, String subject, String details) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO myg_report(type,reporter_id,target_user_id,subject,details) VALUES (?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, type); ps.setInt(2, reporterId);
            if (targetId == null) ps.setNull(3, Types.BIGINT); else ps.setInt(3, targetId);
            ps.setString(4, subject); ps.setString(5, details); ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) { return keys.next() ? get(keys.getInt(1)) : null; }
        } catch (SQLException e) { LOGGER.error("Could not create report", e); return null; }
    }

    public static Report get(int id) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM myg_report WHERE id=?")) {
            ps.setInt(1, id); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        } catch (SQLException e) { LOGGER.error("Could not load report {}", id, e); return null; }
    }

    public static Report getOpenByContactChannel(long channelId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM myg_report WHERE contact_channel_id=? AND status='OPEN' AND contact_closed=FALSE")) {
            ps.setString(1, String.valueOf(channelId));
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        } catch (SQLException e) { LOGGER.error("Could not find report contact channel", e); return null; }
    }

    public static Report getOpenContactForUser(int userId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM myg_report WHERE reporter_id=? AND status='OPEN' AND contact_channel_id IS NOT NULL " +
                        "AND contact_closed=FALSE " +
                        "ORDER BY id DESC LIMIT 1")) {
            ps.setInt(1, userId); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        } catch (SQLException e) { LOGGER.error("Could not find user report contact", e); return null; }
    }

    public static boolean setMessageId(int reportId, long messageId) {
        return update("UPDATE myg_report SET message_id=? WHERE id=?", ps -> {
            ps.setString(1, String.valueOf(messageId)); ps.setInt(2, reportId);
        });
    }

    public static boolean setContactChannel(int reportId, long channelId) {
        return update("UPDATE myg_report SET contact_channel_id=? WHERE id=? AND status='OPEN'", ps -> {
            ps.setString(1, String.valueOf(channelId)); ps.setInt(2, reportId);
        });
    }

    public static boolean setContactDmMessage(int reportId, long messageId, String initialTranscript) {
        return update("UPDATE myg_report SET contact_dm_message_id=?,contact_transcript=?,contact_closed=FALSE " +
                "WHERE id=? AND status='OPEN'", ps -> {
            ps.setString(1, String.valueOf(messageId)); ps.setString(2, initialTranscript); ps.setInt(3, reportId);
        });
    }

    public static boolean appendContactLine(int reportId, String line) {
        return update("UPDATE myg_report SET contact_transcript=CONCAT(COALESCE(contact_transcript,''),?) " +
                "WHERE id=? AND status='OPEN' AND contact_closed=FALSE", ps -> {
            ps.setString(1, line); ps.setInt(2, reportId);
        });
    }

    public static boolean closeContact(int reportId) {
        return update("UPDATE myg_report SET contact_closed=TRUE WHERE id=? AND status='OPEN' AND contact_closed=FALSE",
                ps -> ps.setInt(1, reportId));
    }

    public static boolean finish(int reportId, Status status, String message, long moderatorDiscordId) {
        if (status == Status.OPEN) return false;
        return update("UPDATE myg_report SET status=?,resolution_message=?,moderator_discord_id=?," +
                "resolved_at=CURRENT_TIMESTAMP WHERE id=? AND status='OPEN'", ps -> {
            ps.setString(1, status.name()); ps.setString(2, message);
            ps.setString(3, String.valueOf(moderatorDiscordId)); ps.setInt(4, reportId);
        });
    }

    private static Report map(ResultSet rs) throws SQLException {
        Object targetUser = rs.getObject("target_user_id");
        return new Report(rs.getInt("id"), rs.getString("type"), rs.getInt("reporter_id"),
                targetUser == null ? null : ((Number) targetUser).intValue(),
                rs.getString("subject"), rs.getString("details"),
                Status.valueOf(rs.getString("status")), rs.getLong("message_id"), rs.getLong("contact_channel_id"),
                rs.getLong("contact_dm_message_id"), rs.getString("contact_transcript"),
                rs.getBoolean("contact_closed"));
    }

    private static void addColumnIfMissing(Connection conn, String column, String definition) throws SQLException {
        try (ResultSet columns = conn.getMetaData().getColumns(conn.getCatalog(), null, "myg_report", column)) {
            if (!columns.next()) try (Statement statement = conn.createStatement()) {
                statement.executeUpdate("ALTER TABLE myg_report ADD COLUMN " + column + " " + definition);
            }
        }
    }

    private static boolean update(String sql, Binder binder) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps); return ps.executeUpdate() == 1;
        } catch (SQLException e) { LOGGER.error("Could not update report", e); return false; }
    }

    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }
}
