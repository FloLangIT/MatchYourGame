package de.flolang.matchyourgame.database.game;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public static ArrayList<GameObject> getSubGames(int id) {
        try(Connection conn = Database.getConnection()) {
            ArrayList<GameObject> subGames = new ArrayList<>();
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM game WHERE sub_game_from = ?");
            preparedStatement.setInt(1, id);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                GameObject gameObject = new GameObject(resultSet.getInt("id"), resultSet.getInt("sub_game_from"), resultSet.getString("name"), resultSet.getBoolean("skillbased"), resultSet.getBoolean("active"));
                subGames.add(gameObject);
            }
            LOGGER.trace("Get sub games for game with id {}", id);
            return subGames;
        } catch (SQLException e) {
            LOGGER.error("Error while getting sub games for game with id {}", id, e);
            return new ArrayList<>();
        }
    }

    public static ArrayList<GameObject> getAllGames() {
        try(Connection conn = Database.getConnection()) {
            ArrayList<GameObject> allGames = new ArrayList<>();
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM game");
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                GameObject gameObject = new GameObject(resultSet.getInt("id"), resultSet.getInt("sub_game_from"), resultSet.getString("name"), resultSet.getBoolean("skillbased"), resultSet.getBoolean("active"));
                allGames.add(gameObject);
            }
            LOGGER.trace("Get all games");
            return allGames;
        } catch (SQLException e) {
            LOGGER.error("Error while getting all games", e);
            return new ArrayList<>();
        }
    }

    public static ArrayList<GameObject> getAllMainGames() {
        try (Connection conn = Database.getConnection()) {
            ArrayList<GameObject> allMainGames = new ArrayList<>();
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM game WHERE sub_game_from IS NULL");
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                GameObject gameObject = new GameObject(resultSet.getInt("id"), 0, resultSet.getString("name"), resultSet.getBoolean("skillbased"), resultSet.getBoolean("active"));
                allMainGames.add(gameObject);
            }
            LOGGER.trace("Get all main games");
            return allMainGames;
        } catch (SQLException e) {
            LOGGER.error("Error while getting all main games", e);
            return new ArrayList<>();
        }
    }

    public static Map<Integer, GameActivityStats> activityStats() {
        String sql = "SELECT g.id," +
                "(SELECT COUNT(DISTINCT sp.user_id) FROM search_profile sp " +
                "JOIN game profile_game ON profile_game.id=sp.game_id " +
                "WHERE sp.passive_enabled=TRUE AND (sp.game_id=g.id OR " +
                "(g.sub_game_from IS NULL AND profile_game.sub_game_from=g.id))) passive_users," +
                "(SELECT COUNT(*) FROM lobby l JOIN game lobby_game ON lobby_game.id=l.game_id " +
                "WHERE l.status='OPEN' AND (l.game_id=g.id OR " +
                "(g.sub_game_from IS NULL AND lobby_game.sub_game_from=g.id)) AND " +
                "(SELECT COUNT(*) FROM lobby_member lm WHERE lm.lobby_id=l.id AND lm.left_at IS NULL)<l.max_players) open_lobbies " +
                "FROM game g";
        Map<Integer, GameActivityStats> result = new HashMap<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.put(rs.getInt("id"), new GameActivityStats(
                    rs.getInt("passive_users"), rs.getInt("open_lobbies")));
        } catch (SQLException e) { LOGGER.error("Could not load game activity statistics", e); }
        return result;
    }

    public static GameActivityStats activityStats(int gameId) {
        return activityStats().getOrDefault(gameId, new GameActivityStats(0, 0));
    }

    public record GameActivityStats(int passiveUsers, int openLobbies) {}

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

    public static GameObject create(int subGameFrom, String name, boolean skillbased, boolean active,
                                    List<GameStatDefinition> statistics) {
        GameObject game = create(subGameFrom, name, skillbased, active);
        if (game != null && statistics != null) {
            for (GameStatDefinition statistic : statistics) {
                GameStatRepository.create(game.getId(), statistic.name(), statistic.scope(),
                        statistic.valueType(), statistic.required());
            }
        }
        return game;
    }

    public static void update(GameObject gameObject) {
        try(Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("UPDATE game SET sub_game_from = ?, name = ?, skillbased = ?, active = ? WHERE id = ?");
            preparedStatement.setInt(1, gameObject.getSubGameFrom().getId());
            preparedStatement.setString(2, gameObject.getName());
            preparedStatement.setBoolean(3, gameObject.isSkillbased());
            preparedStatement.setBoolean(4, gameObject.isActive());
            preparedStatement.setInt(5, gameObject.getId());
            preparedStatement.executeUpdate();
            LOGGER.trace("Updated game with id {}", gameObject.getId());
        } catch (SQLException e) {
            LOGGER.error("Error while updating game", e);
        }
    }

    public static void remove(int id) {
        try(Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("DELETE FROM game WHERE id = ?");
            preparedStatement.setInt(1, id);
            preparedStatement.executeUpdate();
            LOGGER.trace("Removed game with id {}", id);
        } catch (SQLException e) {
            LOGGER.error("Error while removing game", e);
        }
    }

    public static boolean updateBasic(int id, String name, boolean skillbased, boolean active) {
        if (name == null || name.isBlank() || name.length() > 255) return false;
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE game SET name=?,skillbased=?,active=? WHERE id=?")) {
            ps.setString(1, name.trim()); ps.setBoolean(2, skillbased); ps.setBoolean(3, active); ps.setInt(4, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { LOGGER.error("Could not update game {}", id, e); return false; }
    }

}
