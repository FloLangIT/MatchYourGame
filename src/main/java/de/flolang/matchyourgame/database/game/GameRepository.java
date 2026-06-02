package de.flolang.matchyourgame.database.game;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class GameRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameRepository.class);

    public static void init() {
        try(Connection conn = Database.getConnection()) {
            conn.prepareStatement("CREATE TABLE IF NOT EXISTS game (id BIGINT AUTO_INCREMENT," +
                    "sub_game_from BIGINT," +
                    "name VARCHAR(255) NOT NULL," +
                    "skillbased BOOLEAN NOT NULL," +
                    "active BOOLEAN NOT NULL," +
                    "PRIMARY KEY (id))").executeUpdate();
            LOGGER.info("Game table created if not exist");
        } catch (SQLException e) {
            LOGGER.error("Error while creating game table", e);
        }
    }

    public static void setFK() {
        try(Connection conn = Database.getConnection()) {
            conn.prepareStatement("ALTER TABLE game ADD CONSTRAINT fk_subgame FOREIGN KEY (sub_game_from) REFERENCES game(id)").executeUpdate();
        } catch (SQLException e) {}
    }

    public static GameObject get(int id) {
        try(Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM game WHERE id = ?");
            preparedStatement.setInt(1, id);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                GameObject gameObject = new GameObject(resultSet.getInt("id"), resultSet.getInt("sub_game_from"), resultSet.getString("name"), resultSet.getBoolean("skillbased"), resultSet.getBoolean("active"));
                LOGGER.trace("Get game by id {}", id);
                return gameObject;
            }
        } catch (SQLException e) {
            LOGGER.error("Error while getting game with id {}", id, e);
            return null;
        }
        return null;
    }

    public static GameObject create(int subGameFrom, String name, boolean skillbased, boolean active) {
        try(Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement;
            if(subGameFrom == 0) {
                preparedStatement = conn.prepareStatement("INSERT INTO game (name, skillbased, active) VALUES (?, ?, ?)", PreparedStatement.RETURN_GENERATED_KEYS);
                preparedStatement.setString(1, name);
                preparedStatement.setBoolean(2, skillbased);
                preparedStatement.setBoolean(3, active);
            } else {
                preparedStatement = conn.prepareStatement("INSERT INTO game (sub_game_from, name, skillbased, active) VALUES (?, ?, ?, ?)", PreparedStatement.RETURN_GENERATED_KEYS);
                preparedStatement.setInt(1, subGameFrom);
                preparedStatement.setString(2, name);
                preparedStatement.setBoolean(3, skillbased);
                preparedStatement.setBoolean(4, active);
            }
            int affectedRows = preparedStatement.executeUpdate();
            if (affectedRows == 0) {
                LOGGER.error("Creating game failed, no rows affected.");
                return null;
            }
            try (ResultSet generatedKeys = preparedStatement.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    int id = generatedKeys.getInt(1);
                    GameObject gameObject = new GameObject(id, subGameFrom, name, skillbased, active);
                    LOGGER.trace("Created game with id {}", id);
                    return gameObject;
                } else {
                    LOGGER.error("Creating game failed, no ID obtained.");
                    return null;
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Error while creating game", e);
            return null;
        }
    }

}
