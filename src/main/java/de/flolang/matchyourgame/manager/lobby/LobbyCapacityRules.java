package de.flolang.matchyourgame.manager.lobby;

public final class LobbyCapacityRules {
    private LobbyCapacityRules() {}

    public static boolean offersUnrestrictedRankChoice(int capacity, Integer unrestrictedPartySize) {
        return unrestrictedPartySize != null && capacity == unrestrictedPartySize;
    }
}
