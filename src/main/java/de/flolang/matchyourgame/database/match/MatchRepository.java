package de.flolang.matchyourgame.database.match;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

public final class MatchRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(MatchRepository.class);

    private MatchRepository() {}

    public static int create(int lobbyId) {
        String sql = "INSERT INTO lobby_match (lobby_id,sequence_number) SELECT ?,COALESCE(MAX(sequence_number),0)+1 FROM lobby_match WHERE lobby_id=?";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, lobbyId); ps.setInt(2, lobbyId); ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) { return keys.next() ? keys.getInt(1) : 0; }
        } catch (SQLException e) { LOGGER.error("Could not create match", e); return 0; }
    }

    public static int addTeam(int matchId, String name) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO match_team (match_id,name) VALUES (?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, matchId); ps.setString(2, name); ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) { return keys.next() ? keys.getInt(1) : 0; }
        } catch (SQLException e) { LOGGER.error("Could not add match team", e); return 0; }
    }

    public static boolean addParticipant(int matchId, int userId, int teamId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO match_participant (match_id,user_id,team_id) VALUES (?,?,?)")) {
            ps.setInt(1, matchId); ps.setInt(2, userId); ps.setInt(3, teamId); return ps.executeUpdate() == 1;
        } catch (SQLException e) { LOGGER.error("Could not add match participant", e); return false; }
    }

    public static boolean saveStat(int matchId, int definitionId, Integer userId, Integer teamId, String value) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO match_stat_value (match_id,stat_definition_id,user_id,team_id,value_text) VALUES (?,?,?,?,?)")) {
            ps.setInt(1, matchId); ps.setInt(2, definitionId);
            if (userId == null) ps.setNull(3, Types.BIGINT); else ps.setInt(3, userId);
            if (teamId == null) ps.setNull(4, Types.BIGINT); else ps.setInt(4, teamId);
            ps.setString(5, value); return ps.executeUpdate() == 1;
        } catch (SQLException e) { LOGGER.error("Could not save match statistic", e); return false; }
    }

    public static boolean confirm(int matchId, int userId, boolean confirmed, String comment) {
        String sql = "INSERT INTO match_confirmation (match_id,user_id,confirmed,comment) VALUES (?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE confirmed=VALUES(confirmed),comment=VALUES(comment),responded_at=CURRENT_TIMESTAMP";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, matchId); ps.setInt(2, userId); ps.setBoolean(3, confirmed); ps.setString(4, comment); ps.executeUpdate();
            refreshStatus(conn, matchId); return true;
        } catch (SQLException e) { LOGGER.error("Could not confirm match", e); return false; }
    }

    public static boolean isParticipant(int matchId, int userId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM match_participant WHERE match_id=? AND user_id=?")) {
            ps.setInt(1, matchId); ps.setInt(2, userId); try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { return false; }
    }

    public static void submitForConfirmation(int matchId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE lobby_match SET status='PENDING_CONFIRMATION' WHERE id=?")) {
            ps.setInt(1, matchId); ps.executeUpdate();
        } catch (SQLException e) { LOGGER.error("Could not submit match", e); }
    }

    private static void refreshStatus(Connection conn, int matchId) throws SQLException {
        String sql = "SELECT COUNT(*) participants,COUNT(mc.user_id) responses," +
                "SUM(CASE WHEN mc.confirmed=FALSE THEN 1 ELSE 0 END) rejects FROM match_participant mp " +
                "LEFT JOIN match_confirmation mc ON mc.match_id=mp.match_id AND mc.user_id=mp.user_id WHERE mp.match_id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next(); int participants = rs.getInt("participants"); int responses = rs.getInt("responses"); int rejects = rs.getInt("rejects");
                String status = rejects > 0 ? "DISPUTED" : responses == participants && participants > 0 ? "CONFIRMED" : "PENDING_CONFIRMATION";
                try (PreparedStatement update = conn.prepareStatement("UPDATE lobby_match SET status=? WHERE id=?")) {
                    update.setString(1, status); update.setInt(2, matchId); update.executeUpdate();
                }
            }
        }
    }
}
