package de.flolang.matchyourgame.database.game;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
                    "active BOOLEAN NOT NULL DEFAULT TRUE," +
                    "UNIQUE KEY uq_game_stat (game_id, name, scope)," +
                    "FOREIGN KEY (game_id) REFERENCES game(id))").executeUpdate();
            if (!hasColumn(conn, "game_stat_definition", "active"))
                conn.prepareStatement("ALTER TABLE game_stat_definition ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE")
                        .executeUpdate();
            conn.prepareStatement("CREATE TABLE IF NOT EXISTS game_stat_inheritance_override (" +
                    "game_id BIGINT PRIMARY KEY," +
                    "FOREIGN KEY (game_id) REFERENCES game(id) ON DELETE CASCADE)").executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Could not initialize game statistics", e);
        }
    }

    public static GameStatDefinition create(int gameId, String name, GameStatDefinition.Scope scope,
                                            GameStatDefinition.ValueType valueType, boolean required) {
        if (name == null || name.isBlank() || name.trim().length() > 80 || scope == null || valueType == null)
            return null;
        name = name.trim();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO game_stat_definition (game_id,name,scope,value_type,required,active) VALUES (?,?,?,?,?,TRUE) " +
                             "ON DUPLICATE KEY UPDATE value_type=VALUES(value_type),required=VALUES(required),active=TRUE",
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
            try (PreparedStatement find = conn.prepareStatement(
                    "SELECT * FROM game_stat_definition WHERE game_id=? AND name=? AND scope=?")) {
                find.setInt(1, gameId); find.setString(2, name); find.setString(3, scope.name());
                try (ResultSet rs = find.executeQuery()) { if (rs.next()) return map(rs); }
            }
        } catch (SQLException e) {
            LOGGER.error("Could not create statistic {} for game {}", name, gameId, e);
        }
        return null;
    }

    public static List<GameStatDefinition> getForGame(int gameId) {
        List<GameStatDefinition> definitions = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM game_stat_definition WHERE game_id=? AND active=TRUE ORDER BY id")) {
            ps.setInt(1, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) definitions.add(map(rs));
            }
        } catch (SQLException e) {
            LOGGER.error("Could not load statistics for game {}", gameId, e);
        }
        return definitions;
    }

    /**
     * Returns the statistics used for a match. Modes inherit their parent game's
     * definitions by default and may add their own definitions.
     */
    public static List<GameStatDefinition> getEffectiveForGame(int gameId) {
        return getEffectiveForGame(gameId, new HashSet<>());
    }

    private static List<GameStatDefinition> getEffectiveForGame(int gameId, Set<Integer> visited) {
        if (!visited.add(gameId)) return List.of();
        List<GameStatDefinition> definitions = new ArrayList<>();
        GameObject game = GameRepository.get(gameId);
        if (game != null && game.getSubGameFrom() != null && inheritsFromParent(gameId))
            definitions.addAll(getEffectiveForGame(game.getSubGameFrom().getId(), visited));
        List<GameStatDefinition> own = getForGame(gameId);
        Map<String, GameStatDefinition> merged = new LinkedHashMap<>();
        definitions.forEach(definition -> merged.put(statisticKey(definition), definition));
        own.forEach(definition -> merged.put(statisticKey(definition), definition));
        return new ArrayList<>(merged.values());
    }

    public static boolean inheritsFromParent(int gameId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM game_stat_inheritance_override WHERE game_id=?")) {
            ps.setInt(1, gameId);
            try (ResultSet rs = ps.executeQuery()) { return !rs.next(); }
        } catch (SQLException e) {
            LOGGER.error("Could not load statistic inheritance for game {}", gameId, e);
            return true;
        }
    }

    public static boolean setInheritsFromParent(int gameId, boolean inherits) {
        String sql = inherits
                ? "DELETE FROM game_stat_inheritance_override WHERE game_id=?"
                : "INSERT IGNORE INTO game_stat_inheritance_override (game_id) VALUES (?)";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, gameId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            LOGGER.error("Could not update statistic inheritance for game {}", gameId, e);
            return false;
        }
    }

    public static GameStatDefinition get(int id) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM game_stat_definition WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        } catch (SQLException e) {
            LOGGER.error("Could not load statistic {}", id, e);
            return null;
        }
    }

    public static boolean update(int id, int gameId, String name, GameStatDefinition.ValueType valueType) {
        if (name == null || name.isBlank() || name.trim().length() > 80) return false;
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE game_stat_definition SET name=?,value_type=? WHERE id=? AND game_id=? AND active=TRUE")) {
            ps.setString(1, name.trim());
            ps.setString(2, valueType.name());
            ps.setInt(3, id);
            ps.setInt(4, gameId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOGGER.error("Could not update statistic {} for game {}", id, gameId, e);
            return false;
        }
    }

    public static boolean remove(int id, int gameId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE game_stat_definition SET active=FALSE WHERE id=? AND game_id=? AND active=TRUE")) {
            ps.setInt(1, id);
            ps.setInt(2, gameId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOGGER.error("Could not remove statistic {} for game {}", id, gameId, e);
            return false;
        }
    }

    private static GameStatDefinition map(ResultSet rs) throws SQLException {
        return new GameStatDefinition(rs.getInt("id"), rs.getInt("game_id"), rs.getString("name"),
                GameStatDefinition.Scope.valueOf(rs.getString("scope")),
                GameStatDefinition.ValueType.valueOf(rs.getString("value_type")), rs.getBoolean("required"));
    }

    private static String statisticKey(GameStatDefinition definition) {
        return definition.scope().name() + ':' + definition.name().toLowerCase(Locale.ROOT);
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, table, column)) {
            if (columns.next()) return true;
        }
        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null,
                table.toUpperCase(Locale.ROOT), column.toUpperCase(Locale.ROOT))) {
            return columns.next();
        }
    }
}
