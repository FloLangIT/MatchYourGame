package de.flolang.matchyourgame.database.lobby;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.sql.Timestamp;

@AllArgsConstructor @Data
public class LobbyObject {

    private final int id;
    private int gameID;
    private int leaderID;
    private long guildID;
    private long voiceChannelID;
    private final Timestamp createdAt;
    private Timestamp closedAt;

}
