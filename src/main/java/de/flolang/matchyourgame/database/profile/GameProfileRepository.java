package de.flolang.matchyourgame.database.profile;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class GameProfileRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameProfileRepository.class);
    private GameProfileRepository() {}

    public static void init() {
        try (Connection conn = Database.getConnection(); Statement statement = conn.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS game_profile (" +
                    "user_id BIGINT NOT NULL," +
                    "game_id BIGINT NOT NULL," +
                    "platform VARCHAR(40) NOT NULL," +
                    "region VARCHAR(40) NOT NULL," +
                    "rank_value INT NOT NULL DEFAULT 0," +
                    "preferred_role VARCHAR(40) NOT NULL DEFAULT 'ANY'," +
                    "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                    "PRIMARY KEY (user_id,game_id,platform)," +
                    "FOREIGN KEY (user_id) REFERENCES user(id)," +
                    "FOREIGN KEY (game_id) REFERENCES game(id))");
            migratePrimaryKey(conn, statement);
        } catch (SQLException e) { LOGGER.error("Could not initialize game profiles", e); }
    }

    public static GameProfile upsert(int userId, int gameId, String platform, String region,
                                     int rankValue, String preferredRole) {
        String sql = "INSERT INTO game_profile (user_id,game_id,platform,region,rank_value,preferred_role) VALUES (?,?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE platform=VALUES(platform),region=VALUES(region),rank_value=VALUES(rank_value)," +
                "preferred_role=VALUES(preferred_role)";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId); ps.setInt(2, gameId); ps.setString(3, platform); ps.setString(4, region);
            ps.setInt(5, rankValue); ps.setString(6, preferredRole); ps.executeUpdate();
            return get(userId, gameId, platform);
        } catch (SQLException e) { LOGGER.error("Could not save game profile", e); return null; }
    }

    public static GameProfile get(int userId, int gameId) {
        List<GameProfile> profiles = getForGame(userId, gameId);
        return profiles.isEmpty() ? null : profiles.getFirst();
    }

    public static GameProfile get(int userId, int gameId, String platform) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM game_profile WHERE user_id=? AND game_id=? AND platform=?")) {
            ps.setInt(1, userId); ps.setInt(2, gameId); ps.setString(3, platform);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new GameProfile(userId, gameId, rs.getString("platform"), rs.getString("region"),
                        rs.getInt("rank_value"), rs.getString("preferred_role"), rs.getTimestamp("updated_at")) : null;
            }
        } catch (SQLException e) { LOGGER.error("Could not load game profile", e); return null; }
    }

    public static List<GameProfile> getForGame(int userId, int gameId) {
        List<GameProfile> profiles = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM game_profile WHERE user_id=? AND game_id=? ORDER BY platform")) {
            ps.setInt(1, userId); ps.setInt(2, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) profiles.add(new GameProfile(userId, gameId, rs.getString("platform"),
                        rs.getString("region"), rs.getInt("rank_value"), rs.getString("preferred_role"),
                        rs.getTimestamp("updated_at")));
            }
        } catch (SQLException e) { LOGGER.error("Could not list game profiles", e); }
        return profiles;
    }

    public static List<GameProfile> getForUser(int userId) {
        List<GameProfile> profiles = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM game_profile WHERE user_id=? ORDER BY game_id,platform")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) profiles.add(new GameProfile(userId, rs.getInt("game_id"),
                        rs.getString("platform"), rs.getString("region"), rs.getInt("rank_value"),
                        rs.getString("preferred_role"), rs.getTimestamp("updated_at")));
            }
        } catch (SQLException e) { LOGGER.error("Could not list user game profiles", e); }
        return profiles;
    }

    private static void migratePrimaryKey(Connection conn, Statement statement) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet keys = conn.getMetaData().getPrimaryKeys(conn.getCatalog(), null, "game_profile")) {
            while (keys.next()) columns.add(keys.getString("COLUMN_NAME").toLowerCase());
        }
        if (!columns.contains("platform"))
            statement.executeUpdate("ALTER TABLE game_profile DROP PRIMARY KEY," +
                    " ADD PRIMARY KEY (user_id,game_id,platform)");
    }
}
