package de.flolang.matchyourgame.manager.lobby;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobbyCapacityRulesTest {
    @Test
    void configuredFullTeamSizeOffersRankRuleChoice() {
        assertTrue(LobbyCapacityRules.offersUnrestrictedRankChoice(5, 5));
        assertFalse(LobbyCapacityRules.offersUnrestrictedRankChoice(4, 5));
        assertFalse(LobbyCapacityRules.offersUnrestrictedRankChoice(5, null));
    }
}
