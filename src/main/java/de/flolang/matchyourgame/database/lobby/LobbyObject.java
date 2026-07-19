package de.flolang.matchyourgame.database.lobby;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.sql.Timestamp;

@AllArgsConstructor
@Data
public class LobbyObject {
    private final int id;
    private int gameID;
    private int leaderID;
    private long guildID;
    private long voiceChannelID;
    private String voiceInviteUrl;
    private int maxPlayers;
    private String platform;
    private String region;
    private String language;
    private int rankMin;
    private int rankMax;
    private Integer customRankMin;
    private Integer customRankMax;
    private boolean rankRulesUnrestricted;
    private String preferredRole;
    private LobbyStatus status;
    private boolean passiveQueue;
    private boolean clanQueue;
    private Timestamp lastInviteWaveAt;
    private Timestamp voiceCreatedAt;
    private final Timestamp createdAt;
    private Timestamp closedAt;

    public boolean isOpen() {
        return status == LobbyStatus.OPEN;
    }
}
