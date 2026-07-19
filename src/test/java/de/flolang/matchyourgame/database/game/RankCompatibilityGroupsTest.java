package de.flolang.matchyourgame.database.game;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RankCompatibilityGroupsTest {
    @Test
    void createsBidirectionalRulesForOverlappingGroups() {
        Set<RankCompatibilityGroups.Pair> pairs = RankCompatibilityGroups.parse(
                "Iron|Bronze|Silver;Silver|Gold;Platinum 2|Platinum 3|Diamond 1|Diamond 2");

        assertTrue(pairs.contains(new RankCompatibilityGroups.Pair("iron", "silver")));
        assertTrue(pairs.contains(new RankCompatibilityGroups.Pair("silver", "iron")));
        assertTrue(pairs.contains(new RankCompatibilityGroups.Pair("silver", "gold")));
        assertTrue(pairs.contains(new RankCompatibilityGroups.Pair("platinum 2", "diamond 2")));
        assertFalse(pairs.contains(new RankCompatibilityGroups.Pair("iron", "gold")));
    }

    @Test
    void supportsClearingAllRules() {
        assertTrue(RankCompatibilityGroups.parse("-").isEmpty());
    }

    @Test
    void rejectsSingleRankGroups() {
        assertThrows(IllegalArgumentException.class, () -> RankCompatibilityGroups.parse("Diamond"));
    }
}
