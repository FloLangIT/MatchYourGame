package de.flolang.matchyourgame.manager.lobby;

import de.flolang.matchyourgame.database.game.GameOption;

import java.util.List;

public final class RankDisplayFormatter {
    private RankDisplayFormatter() {}

    public static String format(List<GameOption> ranks) {
        if (ranks.isEmpty()) return "-";
        boolean contiguous = true;
        for (int i = 1; i < ranks.size(); i++)
            if (ranks.get(i).sortOrder() != ranks.get(i - 1).sortOrder() + 1) contiguous = false;
        if (contiguous && ranks.size() > 1) return ranks.getFirst().name() + " – " + ranks.getLast().name();
        return ranks.stream().map(GameOption::name).reduce((first, next) -> first + ", " + next).orElse("-");
    }
}
