package de.flolang.matchyourgame.manager;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.Database;
import de.flolang.matchyourgame.database.friend.FriendRepository;
import de.flolang.matchyourgame.database.lobby.ClanRepository;
import de.flolang.matchyourgame.database.lobby.LobbyObject;
import de.flolang.matchyourgame.database.lobby.LobbyRepository;
import de.flolang.matchyourgame.database.party.PartyObject;
import de.flolang.matchyourgame.database.party.PartyRepository;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.logging.DiscordLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

public final class AccountDeletionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountDeletionService.class);
    private AccountDeletionService() {}

    public static boolean anonymize(UserObject user) {
        if (user == null || user.isAnonymized()) return false;
        PartyObject party = PartyRepository.getForUser(user.getId());
        if (party != null) PartyRepository.leaveAndTransferHost(party.id(), user.getId());
        LobbyObject lobby = LobbyRepository.getActiveForUser(user.getId());
        if (lobby != null) {
            LobbyRepository.removeMembers(lobby.getId(), List.of(user.getId()));
            Integer next = LobbyRepository.earliestActiveMember(lobby.getId());
            if (lobby.getLeaderID() == user.getId() && next != null) LobbyRepository.promoteHost(lobby.getId(), next);
            if (next == null) LobbyRepository.close(lobby.getId());
        }
        ClanRepository.leaveAll(user.getId());
        FriendRepository.deleteAllForUser(user.getId());
        removePersonalConfiguration(user.getId());
        boolean changed = UserRepository.anonymize(user.getId());
        if (changed) {
            DiscordLogService.action("ACCOUNT_ANONYMIZED", "Account #" + user.getId() + " wurde anonymisiert");
            if (Main.lobbyService != null) Main.lobbyService.excludeBannedUser(user.getId());
        }
        return changed;
    }

    private static void removePersonalConfiguration(int userId) {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (String sql : List.of(
                        "DELETE FROM game_profile WHERE user_id=?",
                        "DELETE FROM search_profile WHERE user_id=?",
                        "DELETE FROM user_communication_language WHERE user_id=?",
                        "DELETE FROM passive_queue_settings WHERE user_id=?",
                        "DELETE FROM party_invitation WHERE invitee_id=? OR inviter_id=?",
                        "UPDATE lobby_invitation SET status='CANCELLED',responded_at=CURRENT_TIMESTAMP WHERE user_id=? AND status='PENDING'",
                        "UPDATE clan_invitation SET status='DECLINED',responded_at=CURRENT_TIMESTAMP WHERE invitee_id=? AND status='PENDING'")) {
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setInt(1, userId);
                        if (sql.contains(" OR ")) ps.setInt(2, userId);
                        ps.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { LOGGER.error("Could not remove personal configuration for user {}", userId, e); }
    }
}
