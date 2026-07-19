package de.flolang.matchyourgame.database.party;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class PartyRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(PartyRepository.class);

    private PartyRepository() {}

    public static PartyObject create(int hostUserId) {
        if (getForUser(hostUserId) != null) return null;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement party = conn.prepareStatement(
                    "INSERT INTO party (host_user_id) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
                party.setInt(1, hostUserId); party.executeUpdate();
                try (ResultSet keys = party.getGeneratedKeys()) {
                    if (!keys.next()) { conn.rollback(); return null; }
                    int partyId = keys.getInt(1);
                    try (PreparedStatement member = conn.prepareStatement("INSERT INTO party_member (party_id,user_id) VALUES (?,?)")) {
                        member.setInt(1, partyId); member.setInt(2, hostUserId); member.executeUpdate();
                    }
                    conn.commit();
                    return get(partyId);
                }
            } catch (SQLException e) {
                conn.rollback(); throw e;
            } finally { conn.setAutoCommit(true); }
        } catch (SQLException e) {
            LOGGER.error("Could not create party", e);
            return null;
        }
    }

    public static PartyObject getForUser(int userId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT p.id FROM party p JOIN party_member pm ON pm.party_id=p.id WHERE pm.user_id=? AND p.status='OPEN'")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? get(rs.getInt(1)) : null; }
        } catch (SQLException e) {
            LOGGER.error("Could not load party for user", e); return null;
        }
    }

    public static PartyObject get(int partyId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT host_user_id FROM party WHERE id=? AND status='OPEN'")) {
            ps.setInt(1, partyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new PartyObject(partyId, rs.getInt(1), members(conn, partyId));
            }
        } catch (SQLException e) {
            LOGGER.error("Could not load party", e); return null;
        }
    }

    public static List<PartyObject> getAllActive() {
        List<PartyObject> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM party WHERE status='OPEN' ORDER BY activity_at DESC"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                PartyObject party = get(rs.getInt(1));
                if (party != null) result.add(party);
            }
        } catch (SQLException e) { LOGGER.error("Could not list active parties", e); }
        return result;
    }

    public static boolean addMember(int partyId, int userId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO party_member (party_id,user_id) VALUES (?,?)")) {
            ps.setInt(1, partyId); ps.setInt(2, userId);
            boolean added = ps.executeUpdate() == 1;
            if (added) touch(partyId);
            return added;
        } catch (SQLException e) {
            LOGGER.error("Could not add party member", e); return false;
        }
    }

    public static boolean removeMember(int partyId, int userId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM party_member WHERE party_id=? AND user_id=?")) {
            ps.setInt(1, partyId); ps.setInt(2, userId); return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOGGER.error("Could not remove party member", e); return false;
        }
    }

    public static boolean leaveAndTransferHost(int partyId, int userId) {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int host;
                try (PreparedStatement lock = conn.prepareStatement("SELECT host_user_id FROM party WHERE id=? AND status='OPEN' FOR UPDATE")) {
                    lock.setInt(1, partyId); try (ResultSet rs = lock.executeQuery()) {
                        if (!rs.next()) { conn.rollback(); return false; }
                        host = rs.getInt(1);
                    }
                }
                try (PreparedStatement remove = conn.prepareStatement("DELETE FROM party_member WHERE party_id=? AND user_id=?")) {
                    remove.setInt(1, partyId); remove.setInt(2, userId);
                    if (remove.executeUpdate() != 1) { conn.rollback(); return false; }
                }
                if (host == userId) {
                    Integer nextHost = null;
                    try (PreparedStatement next = conn.prepareStatement(
                            "SELECT user_id FROM party_member WHERE party_id=? ORDER BY joined_at,user_id LIMIT 1")) {
                        next.setInt(1, partyId); try (ResultSet rs = next.executeQuery()) { if (rs.next()) nextHost = rs.getInt(1); }
                    }
                    try (PreparedStatement update = conn.prepareStatement(nextHost == null
                            ? "UPDATE party SET status='DISBANDED' WHERE id=?"
                            : "UPDATE party SET host_user_id=? WHERE id=?")) {
                        if (nextHost == null) update.setInt(1, partyId);
                        else { update.setInt(1, nextHost); update.setInt(2, partyId); }
                        update.executeUpdate();
                    }
                }
                conn.commit(); return true;
            } catch (SQLException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { LOGGER.error("Could not leave party and transfer host", e); return false; }
    }

    public static void disband(int partyId) {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement members = conn.prepareStatement("DELETE FROM party_member WHERE party_id=?");
                 PreparedStatement party = conn.prepareStatement("UPDATE party SET status='DISBANDED' WHERE id=?")) {
                members.setInt(1, partyId); members.executeUpdate(); party.setInt(1, partyId); party.executeUpdate(); conn.commit();
            } catch (SQLException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { LOGGER.error("Could not disband party", e); }
    }

    public static void touch(int partyId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE party SET activity_at=CURRENT_TIMESTAMP WHERE id=? AND status='OPEN'")) {
            ps.setInt(1, partyId); ps.executeUpdate();
        } catch (SQLException e) { LOGGER.error("Could not update activity for party {}", partyId, e); }
    }

    public static List<Integer> inactivePartyIds(Instant cutoff) {
        List<Integer> ids = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM party WHERE status='OPEN' AND activity_at<=?")) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getInt(1)); }
        } catch (SQLException e) { LOGGER.error("Could not list inactive parties", e); }
        return ids;
    }

    public static boolean disbandIfInactive(int partyId, Instant cutoff) {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement lock = conn.prepareStatement(
                        "SELECT 1 FROM party WHERE id=? AND status='OPEN' AND activity_at<=? FOR UPDATE")) {
                    lock.setInt(1, partyId); lock.setTimestamp(2, Timestamp.from(cutoff));
                    try (ResultSet rs = lock.executeQuery()) {
                        if (!rs.next()) { conn.rollback(); return false; }
                    }
                }
                try (PreparedStatement invitations = conn.prepareStatement(
                        "UPDATE party_invitation SET status='CANCELLED',responded_at=CURRENT_TIMESTAMP " +
                                "WHERE party_id=? AND status='PENDING'");
                     PreparedStatement members = conn.prepareStatement("DELETE FROM party_member WHERE party_id=?");
                     PreparedStatement party = conn.prepareStatement(
                             "UPDATE party SET status='DISBANDED' WHERE id=? AND status='OPEN'")) {
                    invitations.setInt(1, partyId); invitations.executeUpdate();
                    members.setInt(1, partyId); members.executeUpdate();
                    party.setInt(1, partyId);
                    boolean changed = party.executeUpdate() == 1;
                    conn.commit();
                    return changed;
                }
            } catch (SQLException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { LOGGER.error("Could not expire party {}", partyId, e); return false; }
    }

    private static List<Integer> members(Connection conn, int partyId) throws SQLException {
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT user_id FROM party_member WHERE party_id=? ORDER BY joined_at")) {
            ps.setInt(1, partyId); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getInt(1)); }
        }
        return ids;
    }
}
