package de.flolang.matchyourgame.database.lobby;

import java.sql.Timestamp;

public record LobbyInvitation(int id, int lobbyId, int userId, InvitationSource source,
                              Status status, long discordMessageId, Timestamp sentAt, Timestamp respondedAt) {
    public enum Status { PENDING, ACCEPTED, DECLINED, CANCELLED }
}
