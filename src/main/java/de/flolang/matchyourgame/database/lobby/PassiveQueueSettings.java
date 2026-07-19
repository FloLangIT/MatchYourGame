package de.flolang.matchyourgame.database.lobby;

public record PassiveQueueSettings(int userId, boolean enabled, boolean syncOnlineStatus) {
}
