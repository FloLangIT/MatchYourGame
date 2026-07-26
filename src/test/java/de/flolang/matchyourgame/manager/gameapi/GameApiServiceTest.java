package de.flolang.matchyourgame.manager.gameapi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameApiServiceTest {
    @Test
    void normalizesEquivalentRankNames() {
        assertEquals(GameApiService.normalizeRankName("Immortal 3"),
                GameApiService.normalizeRankName("  IMMORTAL-3 "));
        assertEquals(GameApiService.normalizeRankName("Platinum 1"),
                GameApiService.normalizeRankName("Platinum_1"));
    }

    @Test
    void keepsDifferentRankDivisionsDistinct() {
        assertEquals("gold 2", GameApiService.normalizeRankName("Gold 2"));
        assertEquals("gold 3", GameApiService.normalizeRankName("Gold 3"));
    }
}
