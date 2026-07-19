package de.flolang.matchyourgame.database.game;

public record GameOption(int id, int gameId, Type type, String name, int sortOrder) {
    public enum Type { PLATFORM, REGION, RANK, ROLE }
}
