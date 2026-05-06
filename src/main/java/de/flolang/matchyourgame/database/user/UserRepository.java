package de.flolang.matchyourgame.database.user;

import de.flolang.matchyourgame.database.Database;
import de.flolang.matchyourgame.language.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

public class UserRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserRepository.class);

    public static void init() {
        try(Connection conn = Database.getConnection()) {
           conn.prepareStatement("CREATE TABLE IF NOT EXISTS user (id BIGINT NOT NULL AUTO_INCREMENT," +
                    "username VARCHAR(30) NOT NULL UNIQUE," +
                    "discord_id LONG NOT NULL UNIQUE," +
                   "language VARCHAR(5) NOT NULL," +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "last_change_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                   "create_guild VARCHAR(32) DEFAULT NULL," +
                    "PRIMARY KEY (id))").executeUpdate();
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
                    UserObject userObject = new UserObject(resultSet.getInt("id"), resultSet.getString("username"), resultSet.getLong("discord_id"), Language.valueOf(resultSet.getString("language")), resultSet.getTimestamp("created_at"), resultSet.getTimestamp("last_change_at"), resultSet.getLong("create_guild"));
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
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM user WHERE id = ?");
            preparedStatement.setString(1, username);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if(resultSet.next()) {
                    UserObject userObject = new UserObject(resultSet.getInt("id"), resultSet.getString("username"), resultSet.getLong("discord_id"), Language.valueOf(resultSet.getString("language")), resultSet.getTimestamp("created_at"), resultSet.getTimestamp("last_change_at"), resultSet.getLong("create_guild"));
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
            preparedStatement.setLong(1, discordID);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if(resultSet.next()) {
                    UserObject userObject = new UserObject(resultSet.getInt("id"), resultSet.getString("username"), resultSet.getLong("discord_id"), Language.valueOf(resultSet.getString("language")), resultSet.getTimestamp("created_at"), resultSet.getTimestamp("last_change_at"), resultSet.getLong("create_guild"));
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

    public static void update(UserObject userObject) {
        try(Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("UPDATE user SET username = ? AND discord_id = ? AND language = ? AND last_change_at = CURRENT_TIMESTAMP WHERE id = ?");
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

    public static UserObject createUser(String username, long discordID, Language language, Long createGuild) {
        try(Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = null;
            if(createGuild == 0) {
                preparedStatement = conn.prepareStatement("INSERT INTO user (username, discord_id, language) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
                preparedStatement.setString(1, username);
                preparedStatement.setLong(2, discordID);
                preparedStatement.setString(3, language.name());
            } else {
                preparedStatement = conn.prepareStatement("INSERT INTO user (username, discord_id, language, create_guild) VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
                preparedStatement.setString(1, username);
                preparedStatement.setLong(2, discordID);
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
}
