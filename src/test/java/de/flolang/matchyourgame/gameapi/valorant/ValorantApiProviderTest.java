package de.flolang.matchyourgame.gameapi.valorant;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValorantApiProviderTest {
    private final ValorantApiProvider provider = new ValorantApiProvider();

    @Test
    void exposesEveryCompetitiveTierAsUniqueAdminMappingValue() {
        assertEquals(25, provider.rankValues().size());
        assertEquals(25, new HashSet<>(provider.rankValues().stream().map(value -> value.key()).toList()).size());
        assertEquals("3", provider.rankValues().getFirst().key());
        assertEquals("Iron 1", provider.rankValues().getFirst().label());
        assertEquals("27", provider.rankValues().getLast().key());
        assertEquals("Radiant", provider.rankValues().getLast().label());
    }

    @Test
    void exposesOpponentScoreFieldsForTeamMappings() {
        var keys = provider.statisticFields().stream().map(field -> field.key()).toList();
        assertTrue(keys.contains("team.opponentRoundsWon"));
        assertTrue(keys.contains("team.opponentRoundsPlayed"));
        assertTrue(keys.contains("team.opponentWon"));
    }

    @Test
    void exposesOfficialRiotMatchFieldsAndUsesRiotLoginForOwnershipVerification() {
        var keys = provider.statisticFields().stream().map(field -> field.key()).toList();
        assertEquals("Valorant (Riot API)", provider.displayName());
        assertTrue(provider.supportsAccountLogin());
        assertFalse(provider.supportsManualAccountLink());
        assertTrue(keys.contains("player.characterId"));
        assertTrue(keys.contains("match.mapId"));
        assertTrue(keys.contains("match.queueId"));
    }

    @Test
    void encodesUnicodeRiotIdsAsUrlPathSegments() {
        assertEquals("%E4%B9%9D%20Floexe%20%E4%B9%9D",
                ValorantApiProvider.encode("九 Floexe 九"));
    }
}
