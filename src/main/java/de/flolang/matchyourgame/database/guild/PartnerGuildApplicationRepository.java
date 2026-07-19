package de.flolang.matchyourgame.database.guild;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

public final class PartnerGuildApplicationRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(PartnerGuildApplicationRepository.class);
    private PartnerGuildApplicationRepository() {}

    public enum Status { INVITED, SUBMITTED, ADMIN_APPROVED, ACTIVE, REJECTED, WITHDRAWN }
    public record Application(long guildId, Status status, String note, Integer approvedByUserId,
                              Timestamp invitedAt, Timestamp appliedAt, Timestamp approvedAt,
                              Timestamp confirmedAt) {}

    public static void init() {
        try (Connection conn = Database.getConnection(); Statement statement = conn.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS partner_guild_application (" +
                    "guild_id VARCHAR(32) PRIMARY KEY,status VARCHAR(24) NOT NULL DEFAULT 'INVITED'," +
                    "note TEXT NULL,approved_by_user_id BIGINT NULL," +
                    "invited_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,applied_at TIMESTAMP NULL," +
                    "approved_at TIMESTAMP NULL,confirmed_at TIMESTAMP NULL," +
                    "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                    "FOREIGN KEY(guild_id) REFERENCES guild(guild_id)," +
                    "FOREIGN KEY(approved_by_user_id) REFERENCES user(id))");
            statement.executeUpdate("INSERT IGNORE INTO partner_guild_application(guild_id,status,confirmed_at) " +
                    "SELECT guild_id,'ACTIVE',CURRENT_TIMESTAMP FROM guild WHERE partnerGuild=TRUE");
        } catch (SQLException e) { LOGGER.error("Could not initialize partner guild applications", e); }
    }

    public static boolean inviteIfAbsent(long guildId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO partner_guild_application(guild_id,status) " +
                        "SELECT guild_id,'INVITED' FROM guild WHERE guild_id=? AND partnerGuild=FALSE")) {
            ps.setString(1, String.valueOf(guildId)); return ps.executeUpdate() == 1;
        } catch (SQLException e) { LOGGER.error("Could not invite guild {} to partner program", guildId, e); return false; }
    }

    public static boolean reinviteRejected(long guildId) {
        return update("UPDATE partner_guild_application SET status='INVITED',note=NULL,invited_at=CURRENT_TIMESTAMP," +
                "applied_at=NULL,approved_by_user_id=NULL,approved_at=NULL,confirmed_at=NULL " +
                "WHERE guild_id=? AND status IN ('REJECTED','WITHDRAWN')", ps -> ps.setString(1, String.valueOf(guildId)));
    }

    public static Application get(long guildId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM partner_guild_application WHERE guild_id=?")) {
            ps.setString(1, String.valueOf(guildId));
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        } catch (SQLException | IllegalArgumentException e) { LOGGER.error("Could not load partner application {}", guildId, e); return null; }
    }

    public static boolean submit(long guildId, String note) {
        return update("UPDATE partner_guild_application SET status='SUBMITTED',note=?,applied_at=CURRENT_TIMESTAMP " +
                "WHERE guild_id=? AND status IN ('INVITED','REJECTED','WITHDRAWN')", ps -> {
            ps.setString(1, note); ps.setString(2, String.valueOf(guildId));
        });
    }

    public static boolean approve(long guildId, int adminUserId) {
        return update("UPDATE partner_guild_application SET status='ADMIN_APPROVED',approved_by_user_id=?," +
                "approved_at=CURRENT_TIMESTAMP WHERE guild_id=? AND status='SUBMITTED'", ps -> {
            ps.setInt(1, adminUserId); ps.setString(2, String.valueOf(guildId));
        });
    }

    public static boolean reject(long guildId) {
        return update("UPDATE partner_guild_application SET status='REJECTED',approved_by_user_id=NULL," +
                "approved_at=NULL WHERE guild_id=? AND status='SUBMITTED'", ps -> ps.setString(1, String.valueOf(guildId)));
    }

    public static boolean activate(long guildId) {
        return update("UPDATE partner_guild_application SET status='ACTIVE',confirmed_at=CURRENT_TIMESTAMP " +
                "WHERE guild_id=? AND status='ADMIN_APPROVED'", ps -> ps.setString(1, String.valueOf(guildId)));
    }

    public static boolean withdraw(long guildId) {
        return update("UPDATE partner_guild_application SET status='WITHDRAWN',approved_by_user_id=NULL," +
                        "approved_at=NULL,confirmed_at=NULL WHERE guild_id=? AND status='ACTIVE'",
                ps -> ps.setString(1, String.valueOf(guildId)));
    }

    private static boolean update(String sql, SqlConsumer consumer) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            consumer.accept(ps); return ps.executeUpdate() == 1;
        } catch (SQLException e) { LOGGER.error("Could not update partner guild application", e); return false; }
    }

    private static Application map(ResultSet rs) throws SQLException {
        Object approvedBy = rs.getObject("approved_by_user_id");
        return new Application(Long.parseLong(rs.getString("guild_id")), Status.valueOf(rs.getString("status")),
                rs.getString("note"), approvedBy == null ? null : ((Number) approvedBy).intValue(),
                rs.getTimestamp("invited_at"),
                rs.getTimestamp("applied_at"), rs.getTimestamp("approved_at"), rs.getTimestamp("confirmed_at"));
    }

    @FunctionalInterface private interface SqlConsumer { void accept(PreparedStatement ps) throws SQLException; }
}
