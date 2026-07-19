package de.flolang.matchyourgame.database.game;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class GameOptionRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameOptionRepository.class);

    private GameOptionRepository() {}

    public static void init() {
        try (Connection conn = Database.getConnection(); Statement statement = conn.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS game_option (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "game_id BIGINT NOT NULL," +
                    "option_type VARCHAR(12) NOT NULL," +
                    "name VARCHAR(80) NOT NULL," +
                    "sort_order INT NOT NULL," +
                    "UNIQUE KEY uq_game_option_name (game_id,option_type,name)," +
                    "UNIQUE KEY uq_game_option_order (game_id,option_type,sort_order)," +
                    "FOREIGN KEY (game_id) REFERENCES game(id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS game_option_override (" +
                    "game_id BIGINT NOT NULL," +
                    "option_type VARCHAR(12) NOT NULL," +
                    "PRIMARY KEY (game_id,option_type)," +
                    "FOREIGN KEY (game_id) REFERENCES game(id))");
        } catch (SQLException e) { LOGGER.error("Could not initialize game options", e); }
    }

    public static List<GameOption> get(int gameId, GameOption.Type type) {
        List<GameOption> result = direct(gameId, type);
        if (!result.isEmpty() || hasOverride(gameId, type)) return result;
        GameObject game = GameRepository.get(gameId);
        return game != null && game.getSubGameFrom() != null ? direct(game.getSubGameFrom().getId(), type) : result;
    }

    public static void replace(int gameId, GameOption.Type type, List<String> names) {
        List<String> normalized = new ArrayList<>(new LinkedHashSet<>(names.stream()
                .map(String::trim).filter(name -> !name.isBlank()).toList()));
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement delete = conn.prepareStatement("DELETE FROM game_option WHERE game_id=? AND option_type=?");
                 PreparedStatement override = conn.prepareStatement(
                         "INSERT IGNORE INTO game_option_override (game_id,option_type) VALUES (?,?)");
                 PreparedStatement insert = conn.prepareStatement(
                         "INSERT INTO game_option (game_id,option_type,name,sort_order) VALUES (?,?,?,?)")) {
                delete.setInt(1, gameId); delete.setString(2, type.name()); delete.executeUpdate();
                override.setInt(1, gameId); override.setString(2, type.name()); override.executeUpdate();
                for (int i = 0; i < normalized.size(); i++) {
                    insert.setInt(1, gameId); insert.setString(2, type.name()); insert.setString(3, normalized.get(i));
                    insert.setInt(4, i); insert.addBatch();
                }
                insert.executeBatch(); conn.commit();
            } catch (SQLException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { throw new IllegalStateException("Game options could not be saved", e); }
    }

    public static GameOption getById(int id) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM game_option WHERE id=?")) {
            ps.setInt(1, id); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        } catch (SQLException e) { LOGGER.error("Could not load game option {}", id, e); return null; }
    }

    private static List<GameOption> direct(int gameId, GameOption.Type type) {
        List<GameOption> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM game_option WHERE game_id=? AND option_type=? ORDER BY sort_order,id")) {
            ps.setInt(1, gameId); ps.setString(2, type.name());
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(map(rs)); }
        } catch (SQLException e) { LOGGER.error("Could not load {} options for game {}", type, gameId, e); }
        return result;
    }

    private static boolean hasOverride(int gameId, GameOption.Type type) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM game_option_override WHERE game_id=? AND option_type=?")) {
            ps.setInt(1, gameId); ps.setString(2, type.name());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { LOGGER.error("Could not check game option override", e); return false; }
    }

    private static GameOption map(ResultSet rs) throws SQLException {
        return new GameOption(rs.getInt("id"), rs.getInt("game_id"),
                GameOption.Type.valueOf(rs.getString("option_type")), rs.getString("name"), rs.getInt("sort_order"));
    }
}
