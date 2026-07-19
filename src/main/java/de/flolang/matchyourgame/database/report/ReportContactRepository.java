package de.flolang.matchyourgame.database.report;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class ReportContactRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReportContactRepository.class);

    private ReportContactRepository() {}

    public static boolean hadContact(int reporterId, int targetId) {
        if (reporterId == targetId) return false;
        String sql = "SELECT 1 WHERE " +
                "EXISTS (SELECT 1 FROM lobby_member a JOIN lobby_member b ON b.lobby_id=a.lobby_id " +
                "WHERE a.user_id=? AND b.user_id=?) OR " +
                "EXISTS (SELECT 1 FROM lobby_invitation li JOIN lobby l ON l.id=li.lobby_id " +
                "WHERE (l.leader_id=? AND li.user_id=?) OR (l.leader_id=? AND li.user_id=?)) OR " +
                "EXISTS (SELECT 1 FROM friends f WHERE f.accepted_at IS NOT NULL AND " +
                "((f.requester_id=? AND f.receiver_id=?) OR (f.requester_id=? AND f.receiver_id=?))) OR " +
                "EXISTS (SELECT 1 FROM party_invitation pi WHERE " +
                "(pi.inviter_id=? AND pi.invitee_id=?) OR (pi.inviter_id=? AND pi.invitee_id=?)) OR " +
                "EXISTS (SELECT 1 FROM party_member a JOIN party_member b ON b.party_id=a.party_id " +
                "WHERE a.user_id=? AND b.user_id=?)";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            ps.setInt(index++, reporterId); ps.setInt(index++, targetId);
            ps.setInt(index++, reporterId); ps.setInt(index++, targetId);
            ps.setInt(index++, targetId); ps.setInt(index++, reporterId);
            ps.setInt(index++, reporterId); ps.setInt(index++, targetId);
            ps.setInt(index++, targetId); ps.setInt(index++, reporterId);
            ps.setInt(index++, reporterId); ps.setInt(index++, targetId);
            ps.setInt(index++, targetId); ps.setInt(index++, reporterId);
            ps.setInt(index++, reporterId); ps.setInt(index, targetId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            LOGGER.error("Could not verify report contact between {} and {}", reporterId, targetId, e);
            return false;
        }
    }
}
