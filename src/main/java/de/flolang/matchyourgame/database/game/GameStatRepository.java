package de.flolang.matchyourgame.database.game;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class GameStatRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameStatRepository.class);

    private GameStatRepository() {}

    public static void init() {
        try (Connection conn = Database.getConnection()) {
            conn.prepareStatement("CREATE TABLE IF NOT EXISTS game_stat_definition (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "game_id BIGINT NOT NULL," +
                    "name VARCHAR(80) NOT NULL," +
                    "scope VARCHAR(10) NOT NULL," +
                    "value_type VARCHAR(10) NOT NULL," +
                    "required BOOLEAN NOT NULL DEFAULT TRUE," +
                    "UNIQUE KEY uq_game_stat (game_id, name, scope)," +
                    "FOREIGN KEY (game_id) REFERENCES game(id))").executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Could not initialize game statistics", e);
        }
    }

    public static GameStatDefinition create(int gameId, String name, GameStatDefinition.Scope scope,
                                            GameStatDefinition.ValueType valueType, boolean required) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO game_stat_definition (game_id,name,scope,value_type,required) VALUES (?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, gameId);
            ps.setString(2, name);
            ps.setString(3, scope.name());
            ps.setString(4, valueType.name());
            ps.setBoolean(5, required);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return new GameStatDefinition(keys.getInt(1), gameId, name, scope, valueType, required);
            }
        } catch (SQLException e) {
            LOGGER.error("Could not create statistic {} for game {}", name, gameId, e);
        }
        return null;
    }

    public static List<GameStatDefinition> getForGame(int gameId) {
        List<GameStatDefinition> definitions = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM game_stat_definition WHERE game_id=? ORDER BY id")) {
            ps.setInt(1, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) definitions.add(map(rs));
            }
        } catch (SQLException e) {
            LOGGER.error("Could not load statistics for game {}", gameId, e);
        }
        return definitions;
    }

    private static GameStatDefinition map(ResultSet rs) throws SQLException {
        return new GameStatDefinition(rs.getInt("id"), rs.getInt("game_id"), rs.getString("name"),
                GameStatDefinition.Scope.valueOf(rs.getString("scope")),
                GameStatDefinition.ValueType.valueOf(rs.getString("value_type")), rs.getBoolean("required"));
    }
}
