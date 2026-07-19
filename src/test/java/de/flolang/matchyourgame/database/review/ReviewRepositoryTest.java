package de.flolang.matchyourgame.database.review;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ReviewRepositoryTest {
    @Test
    void unseenTargetsAreAlwaysPreferred() {
        Map<Integer, Integer> selected = ReviewRepository.selectTargets(List.of(1, 2, 3), Map.of(
                1, Set.of(2),
                2, Set.of(3),
                3, Set.of(1)));

        assertEquals(3, selected.get(1));
        assertEquals(1, selected.get(2));
        assertEquals(2, selected.get(3));
    }

    @Test
    void repeatedTargetIsAllowedOnlyWithoutAnUnseenAlternative() {
        Map<Integer, Integer> selected = ReviewRepository.selectTargets(List.of(1, 2, 3), Map.of(
                1, Set.of(2, 3),
                2, Set.of(1, 3),
                3, Set.of(1, 2)));

        selected.forEach((reviewer, target) -> assertNotEquals(reviewer, target));
        assertEquals(Set.of(1, 2, 3), Set.copyOf(selected.values()));
    }
}
