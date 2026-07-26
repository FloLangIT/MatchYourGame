package de.flolang.matchyourgame.database.history;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public final class LobbyHistoryRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(LobbyHistoryRepository.class);

    private LobbyHistoryRepository() {}

    public static List<GameHistory> gamesForUser(int userId) {
        String sql = "SELECT g.id game_id,g.name game_name,COUNT(DISTINCT l.id) lobby_count," +
                "COUNT(DISTINCT lm.id) match_count FROM lobby_member mine " +
                "JOIN lobby l ON l.id=mine.lobby_id JOIN game g ON g.id=l.game_id " +
                "LEFT JOIN lobby_match lm ON lm.lobby_id=l.id " +
                "WHERE mine.user_id=? AND l.closed_at IS NOT NULL GROUP BY g.id,g.name " +
                "ORDER BY MAX(l.closed_at) DESC,g.name";
        List<GameHistory> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new GameHistory(rs.getInt("game_id"), rs.getString("game_name"),
                        rs.getInt("lobby_count"), rs.getInt("match_count")));
            }
        } catch (SQLException exception) {
            LOGGER.error("Could not load game history for user {}", userId, exception);
        }
        return result;
    }

    public static int lobbyCount(int userId, int gameId, Integer teammateId) {
        String sql = "SELECT COUNT(*) FROM lobby_member mine JOIN lobby l ON l.id=mine.lobby_id " +
                "WHERE mine.user_id=? AND l.game_id=? AND l.closed_at IS NOT NULL" +
                teammateClause(teammateId);
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bindHistory(ps, userId, gameId, teammateId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException exception) {
            LOGGER.error("Could not count lobby history for user {}", userId, exception);
            return 0;
        }
    }

    public static List<LobbyHistory> lobbiesForUser(int userId, int gameId, Integer teammateId,
                                                     int offset, int limit) {
        String sql = "SELECT l.id,l.game_id,l.leader_id,l.status,l.platform,l.region,l.max_players," +
                "l.created_at,l.closed_at,COUNT(DISTINCT lm.id) match_count " +
                "FROM lobby_member mine JOIN lobby l ON l.id=mine.lobby_id " +
                "LEFT JOIN lobby_match lm ON lm.lobby_id=l.id " +
                "WHERE mine.user_id=? AND l.game_id=? AND l.closed_at IS NOT NULL" +
                teammateClause(teammateId) +
                " GROUP BY l.id,l.game_id,l.leader_id,l.status,l.platform,l.region,l.max_players," +
                "l.created_at,l.closed_at ORDER BY l.closed_at DESC,l.id DESC LIMIT ? OFFSET ?";
        List<LobbyHistory> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            int next = bindHistory(ps, userId, gameId, teammateId);
            ps.setInt(next++, limit);
            ps.setInt(next, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new LobbyHistory(rs.getInt("id"), rs.getInt("game_id"),
                        rs.getInt("leader_id"), rs.getString("status"), rs.getString("platform"),
                        rs.getString("region"), rs.getInt("max_players"), rs.getTimestamp("created_at"),
                        rs.getTimestamp("closed_at"), rs.getInt("match_count")));
            }
        } catch (SQLException exception) {
            LOGGER.error("Could not load lobby history for user {}", userId, exception);
        }
        return result;
    }

    public static List<HistoryMember> teammatesForGame(int userId, int gameId) {
        String sql = "SELECT teammate.user_id,u.username,COUNT(DISTINCT teammate.lobby_id) shared_lobbies " +
                "FROM lobby_member mine JOIN lobby l ON l.id=mine.lobby_id " +
                "JOIN lobby_member teammate ON teammate.lobby_id=l.id AND teammate.user_id<>mine.user_id " +
                "JOIN user u ON u.id=teammate.user_id " +
                "WHERE mine.user_id=? AND l.game_id=? AND l.closed_at IS NOT NULL " +
                "GROUP BY teammate.user_id,u.username ORDER BY shared_lobbies DESC,u.username";
        List<HistoryMember> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new HistoryMember(rs.getInt("user_id"),
                        rs.getString("username"), rs.getInt("shared_lobbies")));
            }
        } catch (SQLException exception) {
            LOGGER.error("Could not load historical teammates for user {}", userId, exception);
        }
        return result;
    }

    public static boolean mayAccessLobby(int userId, int lobbyId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM lobby_member lm JOIN lobby l ON l.id=lm.lobby_id " +
                        "WHERE lm.user_id=? AND lm.lobby_id=? AND l.closed_at IS NOT NULL")) {
            ps.setInt(1, userId);
            ps.setInt(2, lobbyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException exception) {
            LOGGER.error("Could not validate lobby history access", exception);
            return false;
        }
    }

    public static boolean sharedLobby(int firstUserId, int secondUserId, int lobbyId) {
        if (firstUserId == secondUserId) return false;
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM lobby l JOIN lobby_member first_member ON first_member.lobby_id=l.id " +
                        "JOIN lobby_member second_member ON second_member.lobby_id=l.id " +
                        "WHERE l.id=? AND first_member.user_id=? AND second_member.user_id=?")) {
            ps.setInt(1, lobbyId);
            ps.setInt(2, firstUserId);
            ps.setInt(3, secondUserId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException exception) {
            LOGGER.error("Could not validate shared lobby {}", lobbyId, exception);
            return false;
        }
    }

    private static String teammateClause(Integer teammateId) {
        return teammateId == null ? "" : " AND EXISTS (SELECT 1 FROM lobby_member filtered " +
                "WHERE filtered.lobby_id=l.id AND filtered.user_id=?)";
    }

    private static int bindHistory(PreparedStatement ps, int userId, int gameId,
                                   Integer teammateId) throws SQLException {
        int next = 1;
        ps.setInt(next++, userId);
        ps.setInt(next++, gameId);
        if (teammateId != null) ps.setInt(next++, teammateId);
        return next;
    }

    public record GameHistory(int gameId, String gameName, int lobbyCount, int matchCount) {}

    public record LobbyHistory(int lobbyId, int gameId, int leaderId, String status, String platform,
                               String region, int maxPlayers, Timestamp createdAt, Timestamp closedAt,
                               int matchCount) {}

    public record HistoryMember(int userId, String username, int sharedLobbies) {}
}
