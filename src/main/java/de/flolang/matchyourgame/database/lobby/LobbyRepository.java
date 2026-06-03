package de.flolang.matchyourgame.database.lobby;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class LobbyRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(LobbyRepository.class);

    public static void init() {
        try(Connection conn = Database.getConnection()) {
            conn.prepareStatement("CREATE TABLE IF NOT EXISTS lobby (id BIGINT AUTO_INCREMENT," +
                    "game_id BIGINT NOT NULL," +
                    "leader_id BIGINT NOT NULL," +
                    "guild_id VARCHAR(255)," +
                    "voicechannel_ID VARCHAR(255)," +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "closed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "PRIMARY KEY (id)," +
                    "FOREIGN KEY (leader_id) REFERENCES user(id)," +
                    "FOREIGN KEY (guild_id) REFERENCES guild(guild_id))").executeUpdate();
            LOGGER.info("Lobby table created if not exist");
        } catch (SQLException e) {
            LOGGER.error("Error while creating lobby table", e);
        }
    }

    public static void get(int id) {
        try (Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM lobby WHERE id = ?");
            preparedStatement.setInt(1, id);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                LobbyObject lobbyObject = new LobbyObject(resultSet.getInt("id"), resultSet.getInt("game_id"), resultSet.getInt("leader_id"), resultSet.getLong("guild_id"), resultSet.getLong("voicechannel_ID"), resultSet.getTimestamp("created_at"), resultSet.getTimestamp("closed_at"));
                LOGGER.trace("Get lobby by id {}", id);
            }
        } catch (SQLException e) {
            LOGGER.error("Error while getting lobby with id {}", id, e);
        }
    }

    public static LobbyObject getByVoiceChannel(long voiceChannelID) {
        try (Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM lobby WHERE voicechannel_ID = ?");
            preparedStatement.setLong(1, voiceChannelID);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                LobbyObject lobbyObject = new LobbyObject(resultSet.getInt("id"), resultSet.getInt("game_id"), resultSet.getInt("leader_id"), resultSet.getLong("guild_id"), resultSet.getLong("voicechannel_ID"), resultSet.getTimestamp("created_at"), resultSet.getTimestamp("closed_at"));
                LOGGER.trace("Get lobby by voiceChannelID {}", voiceChannelID);
                return lobbyObject;
            }
        } catch (SQLException e) {
            LOGGER.error("Error while getting lobby with voiceChannelID {}", voiceChannelID, e);
        }
        return null;
    }
}
