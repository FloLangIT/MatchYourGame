package de.flolang.matchyourgame.database.profile;

import java.sql.Timestamp;

public record GameProfile(int userId, int gameId, String platform, String region, int rankValue,
                          String preferredRole, Timestamp updatedAt) {
}
