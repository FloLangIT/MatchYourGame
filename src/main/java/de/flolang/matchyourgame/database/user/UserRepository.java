package de.flolang.matchyourgame.database.user;

import de.flolang.matchyourgame.database.Database;
import de.flolang.matchyourgame.language.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class UserRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserRepository.class);

    public static void init() {
        try(Connection conn = Database.getConnection()) {
           conn.prepareStatement("CREATE TABLE IF NOT EXISTS user (id BIGINT NOT NULL AUTO_INCREMENT," +
                    "username VARCHAR(30) NOT NULL UNIQUE," +
                    "discord_id VARCHAR(32) NOT NULL UNIQUE," +
                   "language VARCHAR(5) NOT NULL," +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "last_change_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                   "create_guild VARCHAR(32) DEFAULT NULL," +
                    "PRIMARY KEY (id))").executeUpdate();
                try (ResultSet columns = conn.getMetaData().getColumns(conn.getCatalog(), null, "user",
                        "friend_request_policy")) {
                    if (!columns.next()) conn.prepareStatement("ALTER TABLE user ADD COLUMN friend_request_policy " +
                            "VARCHAR(24) NOT NULL DEFAULT 'EVERYONE'").executeUpdate();
                }
                addColumnIfMissing(conn, "role", "VARCHAR(16) NOT NULL DEFAULT 'USER'");
                addColumnIfMissing(conn, "anonymized", "BOOLEAN NOT NULL DEFAULT FALSE");
                LOGGER.info("User table created if not exist");
        } catch (SQLException e) {
            LOGGER.error("Error while creating user table", e);
        }
    }

    public static void setFK() {
        try(Connection conn = Database.getConnection()) {
            conn.prepareStatement("ALTER TABLE user ADD CONSTRAINT fk_create_guild FOREIGN KEY (create_guild) REFERENCES guild(guild_id)").executeUpdate();
        } catch (SQLException e) {}
    }

    public static UserObject get(int id) {
        try(Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM user WHERE id = ?");
            preparedStatement.setInt(1, id);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if(resultSet.next()) {
                    UserObject userObject = map(resultSet);
                    LOGGER.trace("Get user by id {}", id);
                    return userObject;
                }
            }
            return null;
        } catch (SQLException e) {
            LOGGER.error("Error while getting user with id {}", id, e);
            return null;
        }
    }

    public static UserObject get(String username) {
        try(Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM user WHERE username = ?");
            preparedStatement.setString(1, username);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if(resultSet.next()) {
                    UserObject userObject = map(resultSet);
                    LOGGER.trace("Get user by username {}", username);
                    return userObject;
                }
            }
            return null;
        } catch (SQLException e) {
            LOGGER.error("Error while getting user with username {}", username, e);
            return null;
        }
    }

    public static UserObject get(long discordID) {
        try(Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM user WHERE discord_id = ?");
            preparedStatement.setString(1, String.valueOf(discordID));
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if(resultSet.next()) {
                    UserObject userObject = map(resultSet);
                    LOGGER.trace("Get user by discord {}", discordID);
                    return userObject;
                }
            }
            return null;
        } catch (SQLException e) {
            LOGGER.error("Error while getting user with discordID {}", discordID, e);
            return null;
        }
    }

    public static List<UserObject> getAll() {
        List<UserObject> users = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM user ORDER BY username,id"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) users.add(map(rs));
        } catch (SQLException | IllegalArgumentException e) {
            LOGGER.error("Error while listing users", e);
        }
        return users;
    }

    public static int activeCount() {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM user WHERE anonymized=FALSE"); ResultSet rs = ps.executeQuery()) {
            rs.next(); return rs.getInt(1);
        } catch (SQLException e) { LOGGER.error("Could not count active users", e); return 0; }
    }

    public static void update(UserObject userObject) {
        try(Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("UPDATE user SET username=?,discord_id=?,language=?," +
                    "last_change_at=CURRENT_TIMESTAMP WHERE id=?");
            preparedStatement.setString(1, userObject.getUsername());
            preparedStatement.setLong(2, userObject.getDiscordID());
            preparedStatement.setString(3, userObject.getLanguage().name());
            preparedStatement.setInt(4, userObject.getId());

            preparedStatement.executeUpdate();
            LOGGER.debug("User {} updated", userObject.getId());
        } catch (SQLException e) {
            LOGGER.error("Error while updating user", e);
        }
    }

    public static boolean updateProfile(int userId, String username, Language language) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE user SET username=?,language=?,last_change_at=CURRENT_TIMESTAMP WHERE id=?")) {
            ps.setString(1, username); ps.setString(2, language.name()); ps.setInt(3, userId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOGGER.warn("Could not update profile for user {}", userId, e);
            return false;
        }
    }

    public static FriendRequestPolicy getFriendRequestPolicy(int userId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT friend_request_policy FROM user WHERE id=?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? FriendRequestPolicy.valueOf(rs.getString(1)) : FriendRequestPolicy.EVERYONE;
            }
        } catch (SQLException | IllegalArgumentException e) {
            LOGGER.warn("Could not load friend request policy for user {}", userId, e);
            return FriendRequestPolicy.EVERYONE;
        }
    }

    public static boolean setFriendRequestPolicy(int userId, FriendRequestPolicy policy) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE user SET friend_request_policy=?,last_change_at=CURRENT_TIMESTAMP WHERE id=?")) {
            ps.setString(1, policy.name()); ps.setInt(2, userId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOGGER.warn("Could not update friend request policy for user {}", userId, e);
            return false;
        }
    }

    public static void assignCreateGuildIfMissing(int userId, long guildId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE user SET create_guild=? WHERE id=? AND (create_guild IS NULL OR create_guild='0')")) {
            ps.setString(1, String.valueOf(guildId)); ps.setInt(2, userId); ps.executeUpdate();
        } catch (SQLException e) { LOGGER.warn("Could not assign create guild {} to user {}", guildId, userId, e); }
    }

    public static UserObject createUser(String username, long discordID, Language language, Long createGuild) {
        try(Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = null;
            if(createGuild == 0) {
                preparedStatement = conn.prepareStatement("INSERT INTO user (username, discord_id, language) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
                preparedStatement.setString(1, username);
                preparedStatement.setString(2, String.valueOf(discordID));
                preparedStatement.setString(3, language.name());
            } else {
                preparedStatement = conn.prepareStatement("INSERT INTO user (username, discord_id, language, create_guild) VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
                preparedStatement.setString(1, username);
                preparedStatement.setString(2, String.valueOf(discordID));
                preparedStatement.setString(3, language.name());
                preparedStatement.setLong(4, createGuild);
            }
            preparedStatement.executeUpdate();
            try (ResultSet keys = preparedStatement.getGeneratedKeys()) {
                if (keys.next()) {
                    int id = keys.getInt(1);
                    UserObject user = get(id);
                    if (user != null) {
                        UserCache.put(user);
                    }
                    return user;
                }
            }

            LOGGER.debug("User {} created", username);
        } catch (SQLException e) {
            LOGGER.error("Error while creating user", e);
        }
        return null;
    }

    public static boolean setRole(int userId, UserRole role) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE user SET role=?,last_change_at=CURRENT_TIMESTAMP WHERE id=? AND anonymized=FALSE")) {
            ps.setString(1, role.name()); ps.setInt(2, userId);
            boolean changed = ps.executeUpdate() == 1;
            if (changed) UserCache.evictById(userId);
            return changed;
        } catch (SQLException e) { LOGGER.error("Could not update role for user {}", userId, e); return false; }
    }

    public static boolean anonymize(int userId) {
        UserObject old = get(userId);
        if (old == null || old.isAnonymized()) return false;
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE user SET username=?,discord_id=?,role='USER',anonymized=TRUE," +
                        "friend_request_policy='EVERYONE',last_change_at=CURRENT_TIMESTAMP WHERE id=? AND anonymized=FALSE")) {
            ps.setString(1, "Anonymisierter Benutzer #" + userId);
            ps.setString(2, "-" + userId);
            ps.setInt(3, userId);
            boolean changed = ps.executeUpdate() == 1;
            if (changed) UserCache.evict(old);
            return changed;
        } catch (SQLException e) { LOGGER.error("Could not anonymize user {}", userId, e); return false; }
    }

    private static UserObject map(ResultSet rs) throws SQLException {
        UserRole role;
        try { role = UserRole.valueOf(rs.getString("role")); }
        catch (Exception ignored) { role = UserRole.USER; }
        return new UserObject(rs.getInt("id"), rs.getString("username"),
                Long.parseLong(rs.getString("discord_id")), Language.valueOf(rs.getString("language")),
                rs.getTimestamp("created_at"), rs.getTimestamp("last_change_at"), rs.getLong("create_guild"),
                role, rs.getBoolean("anonymized"));
    }

    private static void addColumnIfMissing(Connection conn, String column, String definition) throws SQLException {
        try (ResultSet columns = conn.getMetaData().getColumns(conn.getCatalog(), null, "user", column)) {
            if (!columns.next()) conn.prepareStatement("ALTER TABLE user ADD COLUMN " + column + " " + definition).executeUpdate();
        }
    }
}
