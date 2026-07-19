package de.flolang.matchyourgame.database.game;

public record GameStatDefinition(int id, int gameId, String name, Scope scope,
                                 ValueType valueType, boolean required) {

    public enum Scope { PLAYER, TEAM }

    public enum ValueType { INTEGER, DECIMAL, BOOLEAN, TEXT }
}
