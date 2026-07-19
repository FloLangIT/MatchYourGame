package de.flolang.matchyourgame.manager.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RatingFormatterTest {
    @Test
    void roundsToWholeStars() {
        assertEquals("★★☆☆☆", RatingFormatter.stars(2.24));
        assertEquals("★★☆☆☆", RatingFormatter.stars(2.49));
        assertEquals("★★★☆☆", RatingFormatter.stars(2.50));
        assertEquals("★★★☆☆", RatingFormatter.stars(2.74));
        assertEquals("★★★☆☆", RatingFormatter.stars(2.75));
    }

    @Test
    void clampsRatingsToFiveStars() {
        assertEquals("☆☆☆☆☆", RatingFormatter.stars(-1));
        assertEquals("★★★★★", RatingFormatter.stars(6));
    }
}
