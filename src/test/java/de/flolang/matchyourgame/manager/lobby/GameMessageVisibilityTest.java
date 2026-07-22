package de.flolang.matchyourgame.manager.lobby;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameMessageVisibilityTest {
    @Test
    void selectsProfileTextVariantForAvailableFields() {
        assertEquals("Entry", GameMessageVisibility.profileVariantKey("Entry", true, true));
        assertEquals("EntryNoRank", GameMessageVisibility.profileVariantKey("Entry", false, true));
        assertEquals("EntryNoRole", GameMessageVisibility.profileVariantKey("Entry", true, false));
        assertEquals("EntryBasic", GameMessageVisibility.profileVariantKey("Entry", false, false));
    }
}
