package de.flolang.matchyourgame.database.guild;

import de.flolang.matchyourgame.database.Database;
import de.flolang.matchyourgame.language.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

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
                    GuildObject guildObject = new GuildObject(resultSet.getLong("guild_id"), resultSet.getInt("manager_user"), resultSet.getLong("myg_voice_category_id"), resultSet.getLong("myg_textchannel_id"), resultSet.getBoolean("partnerGuild"), Language.valueOf(resultSet.getString("language")),resultSet.getTimestamp("added_at"), resultSet.getTimestamp("last_change_at"));
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

    public static GuildObject getByTextChannel(long textChannelID) {
        try(Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM guild WHERE myg_textchannel_id = ?");
            preparedStatement.setLong(1, textChannelID);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if(resultSet.next()) {
                    GuildObject guildObject = new GuildObject(resultSet.getLong("guild_id"), resultSet.getInt("manager_user"), resultSet.getLong("myg_voice_category_id"), resultSet.getLong("myg_textchannel_id"), resultSet.getBoolean("partnerGuild"), Language.valueOf(resultSet.getString("language")),resultSet.getTimestamp("added_at"), resultSet.getTimestamp("last_change_at"));
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
                    GuildObject guildObject = new GuildObject(resultSet.getLong("guild_id"), resultSet.getInt("manager_user"), resultSet.getLong("myg_voice_category_id"), resultSet.getLong("myg_textchannel_id"), resultSet.getBoolean("partnerGuild"), Language.valueOf(resultSet.getString("language")), resultSet.getTimestamp("added_at"), resultSet.getTimestamp("last_change_at"));
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
            LOGGER.debug("Guild {} updated", guildObject.getGuildID());
        } catch (SQLException e) {
            LOGGER.error("Error while updating guild", e);
        }
    }
}
