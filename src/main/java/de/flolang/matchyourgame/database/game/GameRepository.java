package de.flolang.matchyourgame.database.game;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

public class GameRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameRepository.class);

    public static void init() {
        try(Connection conn = Database.getConnection()) {
            conn.prepareStatement("CREATE TABLE IF NOT EXISTS game (id BIGINT AUTO_INCREMENT," +
                    "sub_game_from BIGINT," +
                    "name VARCHAR(255) NOT NULL," +
                    "description VARCHAR(255) NOT NULL," +
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

}
