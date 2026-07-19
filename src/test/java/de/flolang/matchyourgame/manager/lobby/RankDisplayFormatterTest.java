package de.flolang.matchyourgame.manager.lobby;

import de.flolang.matchyourgame.database.game.GameOption;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RankDisplayFormatterTest {
    @Test
    void rendersContiguousRanksUsingNamesInsteadOfNumericBounds() {
        assertEquals("Bronze – Diamond", RankDisplayFormatter.format(List.of(
                rank(8, "Bronze"), rank(9, "Silver"), rank(10, "Gold"), rank(11, "Diamond"))));
    }

    @Test
    void rendersNonContiguousRulesAsExactNameList() {
        assertEquals("Bronze, Gold", RankDisplayFormatter.format(List.of(rank(8, "Bronze"), rank(10, "Gold"))));
    }

    private static GameOption rank(int order, String name) {
        return new GameOption(order, 1, GameOption.Type.RANK, name, order);
    }
}
