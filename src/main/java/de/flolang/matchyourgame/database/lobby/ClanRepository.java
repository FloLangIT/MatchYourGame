package de.flolang.matchyourgame.database.lobby;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

public final class ClanRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClanRepository.class);
    public enum Role { LEADER, ADMIN, MODERATOR, MEMBER }

    private ClanRepository() {}

    public record ClanInfo(int id, String name, int ownerUserId, Timestamp createdAt, int memberCount, Role role) {}
    public record MemberInfo(int userId, Role role, Timestamp joinedAt) {}
    public record Invitation(int id, int clanId, int inviterId, int inviteeId, String status) {}

    public static ClanInfo create(String rawName, int leaderId) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > 60) return null;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement clan = conn.prepareStatement(
                    "INSERT INTO clan (name,owner_user_id) VALUES (?,?)", Statement.RETURN_GENERATED_KEYS)) {
                clan.setString(1, name); clan.setInt(2, leaderId); clan.executeUpdate();
                try (ResultSet keys = clan.getGeneratedKeys()) {
                    if (!keys.next()) { conn.rollback(); return null; }
                    int clanId = keys.getInt(1);
                    try (PreparedStatement member = conn.prepareStatement(
                            "INSERT INTO clan_member (clan_id,user_id,role) VALUES (?,?,'LEADER')")) {
                        member.setInt(1, clanId); member.setInt(2, leaderId); member.executeUpdate();
                    }
                    conn.commit();
                    return getForMember(clanId, leaderId);
                }
            } catch (SQLException exception) { conn.rollback(); throw exception; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { LOGGER.warn("Could not create clan '{}'", name, e); return null; }
    }

    public static boolean rename(int clanId, int actorId, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > 60 || !canAdministrate(clanId, actorId)) return false;
        return update("UPDATE clan SET name=? WHERE id=?", ps -> { ps.setString(1, name); ps.setInt(2, clanId); });
    }

    public static boolean delete(int clanId, int actorId) {
        if (roleOf(clanId, actorId) != Role.LEADER) return false;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (String table : List.of("lobby_clan_queue", "clan_invitation", "clan_game", "clan_member"))
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE clan_id=?")) {
                        ps.setInt(1, clanId); ps.executeUpdate();
                    }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM clan WHERE id=? AND owner_user_id=?")) {
                    ps.setInt(1, clanId); ps.setInt(2, actorId);
                    if (ps.executeUpdate() != 1) { conn.rollback(); return false; }
                }
                conn.commit(); return true;
            } catch (SQLException exception) { conn.rollback(); throw exception; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { LOGGER.error("Could not delete clan {}", clanId, e); return false; }
    }

    public static boolean canAdministrate(int clanId, int userId) {
        Role role = roleOf(clanId, userId);
        return role == Role.LEADER || role == Role.ADMIN;
    }

    public static List<ClanInfo> forUser(int userId) {
        List<ClanInfo> clans = new ArrayList<>();
        String sql = "SELECT c.id,c.name,c.owner_user_id,c.created_at,cm.role,COUNT(all_members.user_id) member_count " +
                "FROM clan c JOIN clan_member cm ON cm.clan_id=c.id AND cm.user_id=? " +
                "LEFT JOIN clan_member all_members ON all_members.clan_id=c.id " +
                "GROUP BY c.id,c.name,c.owner_user_id,c.created_at,cm.role ORDER BY c.name,c.id";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) clans.add(mapInfo(rs));
            }
        } catch (SQLException e) { LOGGER.error("Could not list clans for user {}", userId, e); }
        return clans;
    }

    public static ClanInfo getForMember(int clanId, int userId) {
        String sql = "SELECT c.id,c.name,c.owner_user_id,c.created_at,cm.role,COUNT(all_members.user_id) member_count " +
                "FROM clan c JOIN clan_member cm ON cm.clan_id=c.id AND cm.user_id=? " +
                "LEFT JOIN clan_member all_members ON all_members.clan_id=c.id WHERE c.id=? " +
                "GROUP BY c.id,c.name,c.owner_user_id,c.created_at,cm.role";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId); ps.setInt(2, clanId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? mapInfo(rs) : null; }
        } catch (SQLException e) { LOGGER.error("Could not load clan {}", clanId, e); return null; }
    }

    public static boolean canInvite(int clanId, int userId) {
        Role role = roleOf(clanId, userId);
        return role == Role.LEADER || role == Role.ADMIN || role == Role.MODERATOR;
    }

    public static boolean supportsGame(int clanId, int gameId) {
        String sql = "SELECT 1 FROM clan_game cg JOIN game selected ON selected.id=? " +
                "WHERE cg.clan_id=? AND (cg.game_id=selected.id OR cg.game_id=selected.sub_game_from) LIMIT 1";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, gameId); ps.setInt(2, clanId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { LOGGER.error("Could not check game {} for clan {}", gameId, clanId, e); return false; }
    }

    public static List<ClanInfo> inviteableForGame(int userId, int gameId) {
        List<ClanInfo> clans = new ArrayList<>();
        String sql = "SELECT c.id,c.name,c.owner_user_id,c.created_at,cm.role,COUNT(DISTINCT all_members.user_id) member_count " +
                "FROM clan c JOIN clan_member cm ON cm.clan_id=c.id AND cm.user_id=? " +
                "JOIN game selected ON selected.id=? " +
                "JOIN clan_game cg ON cg.clan_id=c.id AND (cg.game_id=selected.id OR cg.game_id=selected.sub_game_from) " +
                "LEFT JOIN clan_member all_members ON all_members.clan_id=c.id " +
                "WHERE cm.role IN ('LEADER','ADMIN','MODERATOR') " +
                "GROUP BY c.id,c.name,c.owner_user_id,c.created_at,cm.role ORDER BY c.name,c.id";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId); ps.setInt(2, gameId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) clans.add(mapInfo(rs)); }
        } catch (SQLException e) { LOGGER.error("Could not list inviteable clans for game {}", gameId, e); }
        return clans;
    }

    public static Role roleOf(int clanId, int userId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT role FROM clan_member WHERE clan_id=? AND user_id=?")) {
            ps.setInt(1, clanId); ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Role.valueOf(rs.getString(1)) : null; }
        } catch (SQLException e) { LOGGER.error("Could not load clan role", e); return null; }
    }

    public static List<Integer> members(int clanId) {
        List<Integer> ids = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT user_id FROM clan_member WHERE clan_id=?")) {
            ps.setInt(1, clanId); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getInt(1)); }
        } catch (SQLException e) { LOGGER.error("Could not list clan members", e); }
        return ids;
    }

    public static List<MemberInfo> memberDetails(int clanId) {
        List<MemberInfo> members = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT user_id,role,joined_at FROM clan_member WHERE clan_id=? " +
                        "ORDER BY FIELD(role,'LEADER','ADMIN','MODERATOR','MEMBER'),joined_at,user_id")) {
            ps.setInt(1, clanId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) members.add(new MemberInfo(rs.getInt(1), Role.valueOf(rs.getString(2)), rs.getTimestamp(3)));
            }
        } catch (SQLException e) { LOGGER.error("Could not list clan member details", e); }
        return members;
    }

    public static synchronized Invitation invite(int clanId, int actorId, int inviteeId) {
        if (!canInvite(clanId, actorId) || actorId == inviteeId || roleOf(clanId, inviteeId) != null) return null;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement pending = conn.prepareStatement(
                    "SELECT id FROM clan_invitation WHERE clan_id=? AND invitee_id=? " +
                            "AND status='PENDING' LIMIT 1 FOR UPDATE")) {
                pending.setInt(1, clanId); pending.setInt(2, inviteeId);
                try (ResultSet rs = pending.executeQuery()) {
                    if (rs.next()) {
                        conn.rollback();
                        return null;
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO clan_invitation (clan_id,inviter_id,invitee_id) VALUES (?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, clanId); ps.setInt(2, actorId); ps.setInt(3, inviteeId); ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) { conn.rollback(); return null; }
                    Invitation invitation = new Invitation(keys.getInt(1), clanId, actorId, inviteeId, "PENDING");
                    conn.commit();
                    return invitation;
                }
            }
        } catch (SQLException e) { LOGGER.error("Could not create clan invitation", e); return null; }
    }

    public static Invitation invitation(int invitationId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT id,clan_id,inviter_id,invitee_id,status FROM clan_invitation WHERE id=?")) {
            ps.setInt(1, invitationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Invitation(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getString(5)) : null;
            }
        } catch (SQLException e) { LOGGER.error("Could not load clan invitation", e); return null; }
    }

    public static boolean respondToInvitation(int invitationId, int inviteeId, boolean accept) {
        Invitation invitation = invitation(invitationId);
        if (invitation == null || invitation.inviteeId() != inviteeId || !"PENDING".equals(invitation.status())
                || !canInvite(invitation.clanId(), invitation.inviterId()) || roleOf(invitation.clanId(), inviteeId) != null)
            return false;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (accept) try (PreparedStatement member = conn.prepareStatement(
                        "INSERT INTO clan_member (clan_id,user_id,role) VALUES (?,?,'MEMBER')")) {
                    member.setInt(1, invitation.clanId()); member.setInt(2, inviteeId); member.executeUpdate();
                }
                try (PreparedStatement response = conn.prepareStatement(
                        "UPDATE clan_invitation SET status=?,responded_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'")) {
                    response.setString(1, accept ? "ACCEPTED" : "DECLINED"); response.setInt(2, invitationId);
                    if (response.executeUpdate() != 1) { conn.rollback(); return false; }
                }
                conn.commit(); return true;
            } catch (SQLException exception) { conn.rollback(); return false; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { LOGGER.error("Could not respond to clan invitation", e); return false; }
    }

    public static boolean kick(int clanId, int actorId, int targetId) {
        Role actor = roleOf(clanId, actorId), target = roleOf(clanId, targetId);
        boolean allowed = actor == Role.LEADER && target != Role.LEADER
                || actor == Role.ADMIN && target != Role.LEADER
                || actor == Role.MODERATOR && target == Role.MEMBER;
        if (!allowed) return false;
        return update("DELETE FROM clan_member WHERE clan_id=? AND user_id=?", ps -> {
            ps.setInt(1, clanId); ps.setInt(2, targetId);
        });
    }

    public static boolean leave(int clanId, int userId) {
        Role role = roleOf(clanId, userId);
        return role != null && role != Role.LEADER && update(
                "DELETE FROM clan_member WHERE clan_id=? AND user_id=?", ps -> { ps.setInt(1, clanId); ps.setInt(2, userId); });
    }

    public static void leaveAll(int userId) {
        for (ClanInfo clan : forUser(userId)) {
            if (clan.role() != Role.LEADER) { leave(clan.id(), userId); continue; }
            Integer successor = memberDetails(clan.id()).stream().map(MemberInfo::userId)
                    .filter(id -> id != userId).findFirst().orElse(null);
            if (successor == null) delete(clan.id(), userId);
            else if (changeRole(clan.id(), userId, successor, Role.LEADER)) leave(clan.id(), userId);
        }
    }

    public static boolean changeRole(int clanId, int actorId, int targetId, Role newRole) {
        Role actor = roleOf(clanId, actorId), oldRole = roleOf(clanId, targetId);
        if (actor == null || oldRole == null || actorId == targetId || newRole == null) return false;
        if (newRole == Role.LEADER) return actor == Role.LEADER && transferLeadership(clanId, actorId, targetId);
        boolean allowed = actor == Role.LEADER && oldRole != Role.LEADER
                || actor == Role.ADMIN && oldRole != Role.LEADER;
        if (!allowed) return false;
        return update("UPDATE clan_member SET role=? WHERE clan_id=? AND user_id=?", ps -> {
            ps.setString(1, newRole.name()); ps.setInt(2, clanId); ps.setInt(3, targetId);
        });
    }

    private static boolean transferLeadership(int clanId, int actorId, int targetId) {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE clan_member SET role='ADMIN' WHERE clan_id=? AND user_id=?")) {
                    ps.setInt(1, clanId); ps.setInt(2, actorId); ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE clan_member SET role='LEADER' WHERE clan_id=? AND user_id=?")) {
                    ps.setInt(1, clanId); ps.setInt(2, targetId); if (ps.executeUpdate() != 1) { conn.rollback(); return false; }
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE clan SET owner_user_id=? WHERE id=? AND owner_user_id=?")) {
                    ps.setInt(1, targetId); ps.setInt(2, clanId); ps.setInt(3, actorId);
                    if (ps.executeUpdate() != 1) { conn.rollback(); return false; }
                }
                conn.commit(); return true;
            } catch (SQLException exception) { conn.rollback(); throw exception; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { LOGGER.error("Could not transfer clan leadership", e); return false; }
    }

    public static Set<Integer> games(int clanId) {
        Set<Integer> games = new HashSet<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT game_id FROM clan_game WHERE clan_id=?")) {
            ps.setInt(1, clanId); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) games.add(rs.getInt(1)); }
        } catch (SQLException e) { LOGGER.error("Could not list clan games", e); }
        return games;
    }

    public static boolean toggleGame(int clanId, int actorId, int gameId) {
        if (!canAdministrate(clanId, actorId)) return false;
        if (games(clanId).contains(gameId)) return update("DELETE FROM clan_game WHERE clan_id=? AND game_id=?", ps -> {
            ps.setInt(1, clanId); ps.setInt(2, gameId);
        });
        return update("INSERT INTO clan_game (clan_id,game_id) SELECT ?,id FROM game WHERE id=? AND sub_game_from IS NULL", ps -> {
            ps.setInt(1, clanId); ps.setInt(2, gameId);
        });
    }

    @FunctionalInterface private interface SqlBinder { void bind(PreparedStatement statement) throws SQLException; }
    private static boolean update(String sql, SqlBinder binder) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps); return ps.executeUpdate() == 1;
        } catch (SQLException e) { LOGGER.error("Clan update failed", e); return false; }
    }

    private static ClanInfo mapInfo(ResultSet rs) throws SQLException {
        return new ClanInfo(rs.getInt("id"), rs.getString("name"), rs.getInt("owner_user_id"),
                rs.getTimestamp("created_at"), rs.getInt("member_count"), Role.valueOf(rs.getString("role")));
    }

    public static List<QueueCandidate> queuedCandidates(LobbyObject lobby) {
        List<QueueCandidate> result = new ArrayList<>();
        String sql = "SELECT gp.user_id,gp.game_id,gp.platform,gp.region,gp.rank_value,gp.preferred_role, " +
                "COALESCE(AVG((pr.behavior_stars+pr.teamplay_stars+pr.reliability_stars+" +
                "COALESCE(pr.moderation_stars,(pr.behavior_stars+pr.teamplay_stars+pr.reliability_stars)/3.0))/4.0),3) average_rating " +
                "FROM lobby_clan_queue lcq " +
                "JOIN clan_member cm ON cm.clan_id=lcq.clan_id " +
                "JOIN game_profile gp ON gp.user_id=cm.user_id AND gp.game_id=? " +
                "LEFT JOIN review_assignment ra ON ra.target_user_id=gp.user_id " +
                "LEFT JOIN player_review pr ON pr.assignment_id=ra.id " +
                "WHERE lcq.lobby_id=? " +
                "AND gp.user_id NOT IN (SELECT user_id FROM lobby_member WHERE lobby_id=? AND left_at IS NULL) " +
                "AND gp.user_id NOT IN (SELECT user_id FROM lobby_invitation WHERE lobby_id=?) " +
                "GROUP BY gp.user_id,gp.game_id,gp.platform,gp.region,gp.rank_value,gp.preferred_role";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, lobby.getGameID()); ps.setInt(2, lobby.getId()); ps.setInt(3, lobby.getId()); ps.setInt(4, lobby.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new QueueCandidate(new SearchProfile(0, rs.getInt("user_id"),
                        rs.getInt("game_id"), rs.getString("platform"), rs.getString("region"), "ANY",
                        rs.getInt("rank_value"), rs.getString("preferred_role"), false,
                        null), rs.getDouble("average_rating")));
            }
        } catch (SQLException e) { LOGGER.error("Could not load queued clan candidates", e); }
        return result;
    }
}
