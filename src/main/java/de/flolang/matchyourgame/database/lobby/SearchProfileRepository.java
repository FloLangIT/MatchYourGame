package de.flolang.matchyourgame.database.lobby;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import de.flolang.matchyourgame.database.profile.GameProfileRepository;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class SearchProfileRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchProfileRepository.class);

    private SearchProfileRepository() {}

    public static SearchProfile upsert(int userId, int gameId, String platform, String region,
                                       String language, int rank, String role, boolean passive) {
        GameProfileRepository.upsert(userId, gameId, normalize(platform), normalize(region), rank, normalize(role));
        String sql = "INSERT INTO search_profile (user_id,game_id,platform,region,language,rank_value,preferred_role,passive_enabled) " +
                "VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE region=VALUES(region)," +
                "language=VALUES(language),rank_value=VALUES(rank_value),preferred_role=VALUES(preferred_role)," +
                "passive_enabled=VALUES(passive_enabled)";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId); ps.setInt(2, gameId); ps.setString(3, normalize(platform));
            ps.setString(4, normalize(region)); ps.setString(5, normalize(language)); ps.setInt(6, rank);
            ps.setString(7, normalize(role)); ps.setBoolean(8, passive); ps.executeUpdate();
            return get(userId, gameId, platform);
        } catch (SQLException e) {
            LOGGER.error("Could not save search profile", e);
            return null;
        }
    }

    public static SearchProfile get(int userId, int gameId) {
        List<SearchProfile> profiles = getForGame(userId, gameId);
        return profiles.isEmpty() ? null : profiles.getFirst();
    }

    public static SearchProfile get(int userId, int gameId, String platform) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM search_profile WHERE user_id=? AND game_id=? AND platform=?")) {
            ps.setInt(1, userId); ps.setInt(2, gameId); ps.setString(3, normalize(platform));
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        } catch (SQLException e) {
            LOGGER.error("Could not load search profile", e);
            return null;
        }
    }

    public static List<SearchProfile> getForGame(int userId, int gameId) {
        List<SearchProfile> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM search_profile WHERE user_id=? AND game_id=? ORDER BY platform")) {
            ps.setInt(1, userId); ps.setInt(2, gameId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(map(rs)); }
        } catch (SQLException e) { LOGGER.error("Could not list search profiles for game", e); }
        return result;
    }

    public static List<SearchProfile> getForUser(int userId) {
        List<SearchProfile> result = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM search_profile WHERE user_id=? ORDER BY game_id")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(map(rs)); }
        } catch (SQLException e) {
            LOGGER.error("Could not load search profiles for user {}", userId, e);
        }
        return result;
    }

    public static List<QueueCandidate> passiveCandidates(LobbyObject lobby) {
        return passiveCandidates(lobby, true);
    }

    public static List<QueueCandidate> passiveCandidatesIncludingCooldown(LobbyObject lobby) {
        return passiveCandidates(lobby, false);
    }

    private static List<QueueCandidate> passiveCandidates(LobbyObject lobby, boolean enforceCooldown) {
        List<QueueCandidate> result = new ArrayList<>();
        String cooldown = enforceCooldown
                ? "AND NOT EXISTS (SELECT 1 FROM search_profile recent JOIN game recent_game ON recent_game.id=recent.game_id " +
                    "WHERE recent.user_id=sp.user_id AND COALESCE(recent_game.sub_game_from,recent_game.id)=" +
                    "COALESCE(candidate_game.sub_game_from,candidate_game.id) " +
                    "AND recent.last_invited_at>DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 15 MINUTE)) "
                : "";
        String sql = "SELECT sp.*, COALESCE(AVG((pr.behavior_stars+pr.teamplay_stars+pr.reliability_stars+" +
                "COALESCE(pr.moderation_stars,(pr.behavior_stars+pr.teamplay_stars+pr.reliability_stars)/3.0))/4.0),3) average_rating " +
                "FROM search_profile sp " +
                "JOIN game candidate_game ON candidate_game.id=sp.game_id " +
                "LEFT JOIN review_assignment ra ON ra.target_user_id=sp.user_id " +
                "LEFT JOIN player_review pr ON pr.assignment_id=ra.id " +
                "WHERE sp.game_id=? AND sp.passive_enabled=TRUE " +
                cooldown +
                "AND sp.user_id NOT IN (SELECT user_id FROM lobby_member WHERE lobby_id=? AND left_at IS NULL) " +
                "AND sp.user_id NOT IN (SELECT user_id FROM lobby_invitation WHERE lobby_id=?) " +
                "AND sp.user_id NOT IN (SELECT user_id FROM lobby_excluded_user WHERE lobby_id=?) " +
                "GROUP BY sp.id ORDER BY sp.last_invited_at IS NULL DESC, sp.last_invited_at ASC";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, lobby.getGameID()); ps.setInt(2, lobby.getId()); ps.setInt(3, lobby.getId()); ps.setInt(4, lobby.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new QueueCandidate(map(rs), rs.getDouble("average_rating")));
            }
        } catch (SQLException e) {
            LOGGER.error("Could not load passive queue candidates", e);
        }
        return result;
    }

    public static void markInvited(int userId, int gameId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE search_profile sp JOIN game profile_game ON profile_game.id=sp.game_id " +
                             "JOIN game invited_game ON invited_game.id=? SET sp.last_invited_at=CURRENT_TIMESTAMP " +
                             "WHERE sp.user_id=? AND COALESCE(profile_game.sub_game_from,profile_game.id)=" +
                             "COALESCE(invited_game.sub_game_from,invited_game.id)")) {
            ps.setInt(1, gameId); ps.setInt(2, userId); ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Could not mark passive queue invitation", e);
        }
    }

    public static void clearInvitationCooldown(int userId, int gameId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE search_profile sp JOIN game profile_game ON profile_game.id=sp.game_id " +
                        "JOIN game requested_game ON requested_game.id=? SET sp.last_invited_at=NULL " +
                        "WHERE sp.user_id=? AND COALESCE(profile_game.sub_game_from,profile_game.id)=" +
                        "COALESCE(requested_game.sub_game_from,requested_game.id)")) {
            ps.setInt(1, gameId); ps.setInt(2, userId); ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Could not clear PassiveQ invitation cooldown", e);
        }
    }

    private static SearchProfile map(ResultSet rs) throws SQLException {
        return new SearchProfile(rs.getInt("id"), rs.getInt("user_id"), rs.getInt("game_id"),
                rs.getString("platform"), rs.getString("region"), rs.getString("language"),
                rs.getInt("rank_value"), rs.getString("preferred_role"),
                rs.getBoolean("passive_enabled"), rs.getTimestamp("last_invited_at"));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "ANY" : value.trim().toUpperCase();
    }
}
