package de.flolang.matchyourgame.database.party;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class PartyInvitationRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(PartyInvitationRepository.class);

    private PartyInvitationRepository() {}

    public static PartyInvitation create(int partyId, int inviterId, int inviteeId) {
        String sql = "INSERT INTO party_invitation (party_id,inviter_id,invitee_id) VALUES (?,?,?) " +
                "ON DUPLICATE KEY UPDATE inviter_id=VALUES(inviter_id),status='PENDING'," +
                "sent_at=CURRENT_TIMESTAMP,responded_at=NULL";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, partyId);
            ps.setInt(2, inviterId);
            ps.setInt(3, inviteeId);
            ps.executeUpdate();
            return get(partyId, inviteeId);
        } catch (SQLException e) {
            LOGGER.error("Could not create party invitation", e);
            return null;
        }
    }

    public static PartyInvitation get(int invitationId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM party_invitation WHERE id=?")) {
            ps.setInt(1, invitationId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        } catch (SQLException e) {
            LOGGER.error("Could not load party invitation {}", invitationId, e);
            return null;
        }
    }

    public static boolean respond(int invitationId, PartyInvitation.Status status) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE party_invitation SET status=?,responded_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'")) {
            ps.setString(1, status.name());
            ps.setInt(2, invitationId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOGGER.error("Could not respond to party invitation {}", invitationId, e);
            return false;
        }
    }

    private static PartyInvitation get(int partyId, int inviteeId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM party_invitation WHERE party_id=? AND invitee_id=?")) {
            ps.setInt(1, partyId);
            ps.setInt(2, inviteeId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        } catch (SQLException e) {
            LOGGER.error("Could not load party invitation", e);
            return null;
        }
    }

    private static PartyInvitation map(ResultSet rs) throws SQLException {
        return new PartyInvitation(rs.getInt("id"), rs.getInt("party_id"), rs.getInt("inviter_id"),
                rs.getInt("invitee_id"), PartyInvitation.Status.valueOf(rs.getString("status")));
    }
}
