package de.flolang.matchyourgame.database.lobby;

import java.sql.Timestamp;

public record SearchProfile(int id, int userId, int gameId, String platform, String region,
                            String language, int rankValue, String preferredRole,
                            boolean passiveEnabled, Timestamp lastInvitedAt) {
}
