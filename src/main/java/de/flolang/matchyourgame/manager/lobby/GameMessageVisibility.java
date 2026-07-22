package de.flolang.matchyourgame.manager.lobby;

import de.flolang.matchyourgame.database.game.GameObject;
import de.flolang.matchyourgame.database.game.GameOption;
import de.flolang.matchyourgame.database.game.GameOptionRepository;
import de.flolang.matchyourgame.database.game.GameRepository;

public final class GameMessageVisibility {
    private GameMessageVisibility() {}

    public static boolean showsRanks(int gameId) {
        GameObject game = GameRepository.get(gameId);
        return game != null && game.isSkillbased();
    }

    public static boolean showsRoles(int gameId) {
        return !GameOptionRepository.get(gameId, GameOption.Type.ROLE).isEmpty();
    }

    public static String profileVariantKey(String baseKey, int gameId) {
        return profileVariantKey(baseKey, showsRanks(gameId), showsRoles(gameId));
    }

    static String profileVariantKey(String baseKey, boolean ranks, boolean roles) {
        if (ranks && roles) return baseKey;
        if (ranks) return baseKey + "NoRole";
        if (roles) return baseKey + "NoRank";
        return baseKey + "Basic";
    }
}
