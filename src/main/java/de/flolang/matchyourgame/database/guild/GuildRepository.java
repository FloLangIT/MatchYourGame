package de.flolang.matchyourgame.database.guild;

import de.flolang.matchyourgame.database.Database;
import de.flolang.matchyourgame.database.user.UserCache;
import de.flolang.matchyourgame.language.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class GuildRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuildRepository.class);

    public static void init() {
        try(Connection conn = Database.getConnection()) {
            conn.prepareStatement("CREATE TABLE IF NOT EXISTS guild (guild_id VARCHAR(32) NOT NULL," +
                    "manager_user BIGINT NOT NULL ," +
                    "myg_voice_category_id VARCHAR(32)," +
                    "myg_textchannel_id VARCHAR(32) NOT NULL," +
                    "partnerGuild BOOLEAN NOT NULL DEFAULT FALSE," +
                    "language VARCHAR(5) NOT NULL," +
                    "added_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "last_change_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "PRIMARY KEY (guild_id))").executeUpdate();
            addColumnIfMissing(conn, "myg_text_message_id", "VARCHAR(32) NULL");
            addColumnIfMissing(conn, "active", "BOOLEAN NOT NULL DEFAULT TRUE");
            addColumnIfMissing(conn, "partner_operational", "BOOLEAN NOT NULL DEFAULT TRUE");
            addColumnIfMissing(conn, "registration_operational", "BOOLEAN NOT NULL DEFAULT TRUE");
            LOGGER.info("Guild table created if not exist");
        } catch (SQLException e) {
            LOGGER.error("Error while creating guild table", e);
        }
    }

    public static void setFK() {
        try(Connection conn = Database.getConnection()) {
            conn.prepareStatement("ALTER TABLE guild ADD CONSTRAINT fk_manager_user FOREIGN KEY (manager_user) REFERENCES user(id)").executeUpdate();
        } catch (SQLException e) {}
    }

    public static GuildObject get(long guildID) {
        try(Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM guild WHERE guild_id = ?");
            preparedStatement.setLong(1, guildID);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if(resultSet.next()) {
                    GuildObject guildObject = map(resultSet);
                    LOGGER.trace("Get guild by guildID {}", guildID);
                    return guildObject;
                }
            }
            return null;
        } catch (SQLException e) {
            LOGGER.error("Error while getting guild with guildID {}", guildID, e);
            return null;
        }
    }

    public static List<GuildObject> getAll() {
        List<GuildObject> guilds = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM guild ORDER BY added_at,guild_id"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) guilds.add(map(rs));
        } catch (SQLException e) { LOGGER.error("Error while listing guilds", e); }
        return guilds;
    }

    public static List<GuildObject> getManagedBy(int managerUserId) {
        List<GuildObject> guilds = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM guild WHERE manager_user=? ORDER BY added_at,guild_id")) {
            ps.setInt(1, managerUserId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) guilds.add(map(rs));
            }
        } catch (SQLException e) { LOGGER.error("Error while listing guilds managed by {}", managerUserId, e); }
        return guilds;
    }

    public static boolean transferManager(long guildId, int currentManagerId, int newManagerId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE guild SET manager_user=?,last_change_at=CURRENT_TIMESTAMP WHERE guild_id=? AND manager_user=?")) {
            ps.setInt(1, newManagerId);
            ps.setString(2, String.valueOf(guildId));
            ps.setInt(3, currentManagerId);
            boolean updated = ps.executeUpdate() == 1;
            if (updated) {
                GuildObject refreshed = get(guildId);
                if (refreshed != null) GuildCache.put(refreshed);
            }
            return updated;
        } catch (SQLException e) {
            LOGGER.error("Could not transfer manager for guild {} from {} to {}", guildId,
                    currentManagerId, newManagerId, e);
            return false;
        }
    }

    public static LobbyUsage lobbyUsage(long guildId) {
        String sql = "SELECT COUNT(*) hosted_lobbies,SUM(CASE WHEN status IN ('FORMING','READY','ACTIVE') THEN 1 ELSE 0 END) active_lobbies " +
                "FROM lobby WHERE guild_id=? AND voice_created_at IS NOT NULL";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(guildId));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new LobbyUsage(rs.getLong("hosted_lobbies"), rs.getLong("active_lobbies"));
            }
        } catch (SQLException e) { LOGGER.error("Could not load lobby usage for guild {}", guildId, e); }
        return new LobbyUsage(0, 0);
    }

    public static GuildStats stats(long guildId, int managerUserId) {
        String filter = "(u.create_guild=? OR (u.id=? AND (u.create_guild IS NULL OR u.create_guild='0')))";
        String sql = "SELECT COUNT(*) account_count,AVG(r.rating) average_rating FROM user u LEFT JOIN (" +
                "SELECT ra.target_user_id,AVG((pr.behavior_stars+pr.teamplay_stars+pr.reliability_stars+" +
                "COALESCE(pr.moderation_stars,(pr.behavior_stars+pr.teamplay_stars+pr.reliability_stars)/3.0))/4.0) rating " +
                "FROM review_assignment ra JOIN player_review pr ON pr.assignment_id=ra.id " +
                "WHERE ra.completed_at IS NOT NULL GROUP BY ra.target_user_id) r ON r.target_user_id=u.id WHERE " + filter;
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(guildId)); ps.setInt(2, managerUserId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int accountCount = rs.getInt("account_count");
                    double rating = rs.getDouble("average_rating");
                    boolean ratingMissing = rs.wasNull();
                    return new GuildStats(accountCount, ratingMissing ? null : rating);
                }
            }
        } catch (SQLException e) { LOGGER.error("Could not load statistics for guild {}", guildId, e); }
        return new GuildStats(0, null);
    }

    public static boolean setPartnerGuild(long guildId, boolean partner, long voiceCategoryId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE guild SET partnerGuild=?,partner_operational=?,myg_voice_category_id=?," +
                        "last_change_at=CURRENT_TIMESTAMP WHERE guild_id=?")) {
            ps.setBoolean(1, partner); ps.setBoolean(2, partner);
            ps.setString(3, String.valueOf(voiceCategoryId));
            ps.setString(4, String.valueOf(guildId)); return ps.executeUpdate() == 1;
        } catch (SQLException e) { LOGGER.error("Could not update partner status for guild {}", guildId, e); return false; }
    }

    public static boolean withdrawPartnerGuild(long guildId, int managerUserId) {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement guild = conn.prepareStatement(
                    "UPDATE guild SET partnerGuild=FALSE,last_change_at=CURRENT_TIMESTAMP " +
                            "WHERE guild_id=? AND manager_user=? AND partnerGuild=TRUE");
                 PreparedStatement application = conn.prepareStatement(
                         "UPDATE partner_guild_application SET status='WITHDRAWN',approved_by_user_id=NULL," +
                                 "approved_at=NULL,confirmed_at=NULL WHERE guild_id=? AND status='ACTIVE'")) {
                guild.setString(1, String.valueOf(guildId));
                guild.setInt(2, managerUserId);
                if (guild.executeUpdate() != 1) { conn.rollback(); return false; }
                application.setString(1, String.valueOf(guildId));
                if (application.executeUpdate() != 1) { conn.rollback(); return false; }
                conn.commit();
                GuildObject refreshed = get(guildId);
                if (refreshed != null) GuildCache.put(refreshed);
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            LOGGER.error("Could not withdraw partner guild {}", guildId, e);
            return false;
        }
    }

    public record GuildStats(int accountCount, Double averageRating) {}
    public record LobbyUsage(long hostedLobbies, long activeLobbies) {}

    public static GuildObject getByTextChannel(long textChannelID) {
        try(Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM guild WHERE myg_textchannel_id = ?");
            preparedStatement.setLong(1, textChannelID);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if(resultSet.next()) {
                    GuildObject guildObject = map(resultSet);
                    LOGGER.trace("Get guild by textChannelID {}", textChannelID);
                    return guildObject;
                }
            }
            return null;
        } catch (SQLException e) {
            LOGGER.error("Error while getting guild with textChannelID {}", textChannelID, e);
            return null;
        }
    }

    public static GuildObject getByCategory(long categoryID) {
        try(Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM guild WHERE myg_voice_category_id = ?");
            preparedStatement.setLong(1, categoryID);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if(resultSet.next()) {
                    GuildObject guildObject = map(resultSet);
                    LOGGER.trace("Get guild by categoryID {}", categoryID);
                    return guildObject;
                }
            }
            return null;
        } catch (SQLException e) {
            LOGGER.error("Error while getting guild with categoryID {}", categoryID, e);
            return null;
        }
    }

    public static List<GuildObject> getPartnerGuilds() {
        List<GuildObject> guilds = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM guild WHERE partnerGuild=TRUE AND active=TRUE " +
                     "AND partner_operational=TRUE AND myg_voice_category_id IS NOT NULL")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) guilds.add(map(rs));
            }
        } catch (SQLException e) {
            LOGGER.error("Error while getting partner guilds", e);
        }
        return guilds;
    }


    public static GuildObject createGuild(long guildID, int managerUser, long mygVoiceCategoryId, long mygTextChannelId, Language language) {
        try(Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("INSERT INTO guild (guild_id, manager_user, myg_textchannel_id, language) VALUES (?, ?, ?, ?)");
            preparedStatement.setLong(1, guildID);
            preparedStatement.setInt(2, managerUser);
            preparedStatement.setLong(3, mygTextChannelId);
            preparedStatement.setString(4, language.name());
            preparedStatement.executeUpdate();
            GuildObject guild = get(guildID);
            if (guild != null) {
                GuildCache.put(guild);
            }
            LOGGER.debug("Guild {} created", guild);
            return guild;
        } catch (SQLException e) {
            LOGGER.error("Error while creating user", e);
        }
        return null;
    }

    public static void updateGuild(GuildObject guildObject) {
        try(Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("UPDATE guild SET manager_user = ?, myg_voice_category_id = ?, myg_textchannel_id = ?, partnerGuild = ?, language = ?, last_change_at = CURRENT_TIMESTAMP WHERE guild_id = ?");
            preparedStatement.setInt(1, guildObject.getManagerUser().getId());
            preparedStatement.setLong(2, guildObject.getMygVoiceCategoryId());
            preparedStatement.setLong(3, guildObject.getMygTextChannelId());
            preparedStatement.setBoolean(4, guildObject.isPartnerGuild());
            preparedStatement.setString(5, guildObject.getLanguage().name());
            preparedStatement.setLong(6, guildObject.getGuildID());
            preparedStatement.executeUpdate();
            LOGGER.debug("Guild {} updated", guildObject.getGuildID());
        } catch (SQLException e) {
            LOGGER.error("Error while updating guild", e);
        }
    }

    public static boolean setActive(long guildId, boolean active) {
        return updateBooleanState(guildId, "active", active);
    }

    public static boolean setPartnerOperational(long guildId, boolean operational) {
        return updateBooleanState(guildId, "partner_operational", operational);
    }

    public static boolean setRegistrationOperational(long guildId, boolean operational) {
        return updateBooleanState(guildId, "registration_operational", operational);
    }

    public static boolean setRegistrationTarget(long guildId, long channelId, long messageId, boolean operational) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE guild SET myg_textchannel_id=?,myg_text_message_id=?,registration_operational=?," +
                        "last_change_at=CURRENT_TIMESTAMP WHERE guild_id=?")) {
            ps.setString(1, String.valueOf(channelId));
            ps.setString(2, String.valueOf(messageId));
            ps.setBoolean(3, operational); ps.setString(4, String.valueOf(guildId));
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { LOGGER.error("Could not update registration target for guild {}", guildId, e); return false; }
    }

    public static boolean deleteConfiguration(long guildId) {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement users = conn.prepareStatement("UPDATE user SET create_guild=NULL WHERE create_guild=?");
                 PreparedStatement lobbies = conn.prepareStatement("UPDATE lobby SET guild_id=NULL WHERE guild_id=?");
                 PreparedStatement applications = conn.prepareStatement("DELETE FROM partner_guild_application WHERE guild_id=?");
                 PreparedStatement setupAuthorization = conn.prepareStatement(
                         "DELETE FROM guild_setup_authorization WHERE guild_id=?");
                 PreparedStatement guild = conn.prepareStatement("DELETE FROM guild WHERE guild_id=?")) {
                String id = String.valueOf(guildId);
                users.setString(1, id); users.executeUpdate();
                lobbies.setString(1, id); lobbies.executeUpdate();
                applications.setString(1, id); applications.executeUpdate();
                setupAuthorization.setString(1, id); setupAuthorization.executeUpdate();
                guild.setString(1, id);
                boolean deleted = guild.executeUpdate() == 1;
                if (deleted) {
                    conn.commit();
                    GuildCache.evict(guildId);
                    UserCache.clear();
                } else conn.rollback();
                return deleted;
            } catch (SQLException e) { conn.rollback(); throw e; }
        } catch (SQLException e) { LOGGER.error("Could not delete invalid configuration for guild {}", guildId, e); return false; }
    }

    private static boolean updateBooleanState(long guildId, String column, boolean value) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE guild SET " + column + "=?,last_change_at=CURRENT_TIMESTAMP WHERE guild_id=? AND " + column + "<>?")) {
            ps.setBoolean(1, value); ps.setString(2, String.valueOf(guildId)); ps.setBoolean(3, value);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { LOGGER.error("Could not update {} for guild {}", column, guildId, e); return false; }
    }

    private static GuildObject map(ResultSet rs) throws SQLException {
        return new GuildObject(rs.getLong("guild_id"), rs.getInt("manager_user"),
                rs.getLong("myg_voice_category_id"), rs.getLong("myg_textchannel_id"),
                rs.getLong("myg_text_message_id"), rs.getBoolean("partnerGuild"), rs.getBoolean("active"),
                rs.getBoolean("partner_operational"), rs.getBoolean("registration_operational"),
                Language.valueOf(rs.getString("language")),
                rs.getTimestamp("added_at"), rs.getTimestamp("last_change_at"));
    }

    private static void addColumnIfMissing(Connection conn, String column, String definition) throws SQLException {
        try (ResultSet columns = conn.getMetaData().getColumns(conn.getCatalog(), null, "guild", column)) {
            if (!columns.next()) conn.prepareStatement("ALTER TABLE guild ADD COLUMN " + column + " " + definition).executeUpdate();
        }
    }
}
