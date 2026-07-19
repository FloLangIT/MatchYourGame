package de.flolang.matchyourgame.database.guild;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class GuildSetupAuthorizationRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(GuildSetupAuthorizationRepository.class);

    private GuildSetupAuthorizationRepository() {}

    public static void init() {
        try (Connection conn = Database.getConnection(); Statement statement = conn.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS guild_setup_authorization (" +
                    "guild_id VARCHAR(32) PRIMARY KEY,authorized_discord_id VARCHAR(32) NOT NULL," +
                    "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
        } catch (SQLException e) { LOGGER.error("Could not initialize guild setup authorizations", e); }
    }

    public static boolean authorize(long guildId, long discordId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO guild_setup_authorization(guild_id,authorized_discord_id) VALUES(?,?) " +
                        "ON DUPLICATE KEY UPDATE authorized_discord_id=VALUES(authorized_discord_id)")) {
            ps.setString(1, String.valueOf(guildId));
            ps.setString(2, String.valueOf(discordId));
            ps.executeUpdate();
            return isAuthorized(guildId, discordId);
        } catch (SQLException e) { LOGGER.error("Could not authorize setup for guild {}", guildId, e); return false; }
    }

    public static boolean isAuthorized(long guildId, long discordId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT authorized_discord_id FROM guild_setup_authorization WHERE guild_id=?")) {
            ps.setString(1, String.valueOf(guildId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && String.valueOf(discordId).equals(rs.getString(1));
            }
        } catch (SQLException e) { LOGGER.error("Could not verify setup authorization for guild {}", guildId, e); return false; }
    }

    /** Allows setup messages created before this table existed to become bound to their first recipient. */
    public static boolean claimIfAbsent(long guildId, long discordId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO guild_setup_authorization(guild_id,authorized_discord_id) VALUES(?,?)")) {
            ps.setString(1, String.valueOf(guildId));
            ps.setString(2, String.valueOf(discordId));
            ps.executeUpdate();
            return isAuthorized(guildId, discordId);
        } catch (SQLException e) { LOGGER.error("Could not claim setup authorization for guild {}", guildId, e); return false; }
    }

    public static void delete(long guildId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM guild_setup_authorization WHERE guild_id=?")) {
            ps.setString(1, String.valueOf(guildId));
            ps.executeUpdate();
        } catch (SQLException e) { LOGGER.error("Could not remove setup authorization for guild {}", guildId, e); }
    }
}
