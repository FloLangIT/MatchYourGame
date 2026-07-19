package de.flolang.matchyourgame.database.lobby;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class LobbyInvitationRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(LobbyInvitationRepository.class);

    private LobbyInvitationRepository() {}

    public static LobbyInvitation create(int lobbyId, int userId, InvitationSource source) {
        String sql = "INSERT INTO lobby_invitation (lobby_id,user_id,source) VALUES (?,?,?)";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, lobbyId); ps.setInt(2, userId); ps.setString(3, source.name()); ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) { if (keys.next()) return get(keys.getInt(1)); }
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            LOGGER.debug("User {} already has an invitation for lobby {}", userId, lobbyId);
        } catch (SQLException e) {
            LOGGER.error("Could not create lobby invitation", e);
        }
        return null;
    }

    public static LobbyInvitation get(int id) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM lobby_invitation WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        } catch (SQLException e) {
            LOGGER.error("Could not load invitation {}", id, e);
            return null;
        }
    }

    public static LobbyInvitation get(int lobbyId, int userId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM lobby_invitation WHERE lobby_id=? AND user_id=?")) {
            ps.setInt(1, lobbyId); ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        } catch (SQLException e) {
            LOGGER.error("Could not load lobby invitation", e);
            return null;
        }
    }

    public static boolean respond(int id, LobbyInvitation.Status status) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE lobby_invitation SET status=?,responded_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'")) {
            ps.setString(1, status.name()); ps.setInt(2, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOGGER.error("Could not update invitation {}", id, e);
            return false;
        }
    }

    public static void setDiscordMessageId(int invitationId, long messageId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE lobby_invitation SET discord_message_id=? WHERE id=?")) {
            ps.setString(1, Long.toString(messageId));
            ps.setInt(2, invitationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Could not store Discord message for lobby invitation {}", invitationId, e);
        }
    }

    public static List<LobbyInvitation> getPendingForLobby(int lobbyId) {
        List<LobbyInvitation> invitations = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM lobby_invitation WHERE lobby_id=? AND status='PENDING'")) {
            ps.setInt(1, lobbyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) invitations.add(map(rs));
            }
        } catch (SQLException e) {
            LOGGER.error("Could not load pending invitations for lobby {}", lobbyId, e);
        }
        return invitations;
    }

    private static LobbyInvitation map(ResultSet rs) throws SQLException {
        return new LobbyInvitation(rs.getInt("id"), rs.getInt("lobby_id"), rs.getInt("user_id"),
                InvitationSource.valueOf(rs.getString("source")), LobbyInvitation.Status.valueOf(rs.getString("status")),
                parseDiscordId(rs.getString("discord_message_id")), rs.getTimestamp("sent_at"), rs.getTimestamp("responded_at"));
    }

    private static long parseDiscordId(String value) {
        try { return value == null ? 0 : Long.parseLong(value); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
