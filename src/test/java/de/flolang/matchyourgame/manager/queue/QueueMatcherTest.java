package de.flolang.matchyourgame.manager.queue;

import de.flolang.matchyourgame.database.lobby.*;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueueMatcherTest {
    private final Instant now = Instant.parse("2026-07-16T12:00:00Z");

    @Test
    void appliesHardProfileFilters() {
        LobbyObject lobby = lobby();
        assertTrue(QueueMatcher.matches(lobby, profile(1, "PC", "EU", "DE", 500, "SUPPORT", null)));
        assertTrue(QueueMatcher.matches(lobby, profile(1, "ANY", "EU", "DE", 500, "SUPPORT", null)));
        assertFalse(QueueMatcher.matches(lobby, profile(1, "PS5", "EU", "DE", 500, "SUPPORT", null)));
        assertFalse(QueueMatcher.matches(lobby, profile(1, "PC", "EU", "DE", 900, "SUPPORT", null)));
    }

    @Test
    void longWaitingGoodPlayerWinsBeforeRecentlyInvitedPlayer() {
        QueueCandidate waiting = new QueueCandidate(profile(10, "PC", "EU", "DE", 500, "SUPPORT",
                now.minus(20, ChronoUnit.DAYS)), 4.8);
        QueueCandidate recent = new QueueCandidate(profile(11, "PC", "EU", "DE", 500, "SUPPORT",
                now.minus(1, ChronoUnit.HOURS)), 5.0);

        List<QueueCandidate> ranked = QueueMatcher.rank(lobby(), List.of(recent, waiting), now);

        assertEquals(10, ranked.getFirst().profile().userId());
    }

    @Test
    void onlyMatchingCandidatesAreRanked() {
        QueueCandidate matching = new QueueCandidate(profile(10, "PC", "EU", "DE", 500, "SUPPORT", null), 3);
        QueueCandidate wrongRegion = new QueueCandidate(profile(11, "PC", "NA", "DE", 500, "SUPPORT", null), 5);
        assertEquals(List.of(matching), QueueMatcher.rank(lobby(), List.of(wrongRegion, matching), now));
    }

    @Test
    void appliesOptionalHostRankLimits() {
        LobbyObject lobby = lobby(300, 600, false);
        assertFalse(QueueMatcher.matches(lobby, profile(1, "PC", "EU", "DE", 200, "SUPPORT", null)));
        assertTrue(QueueMatcher.matches(lobby, profile(1, "PC", "EU", "DE", 500, "SUPPORT", null)));
        assertFalse(QueueMatcher.matches(lobby, profile(1, "PC", "EU", "DE", 700, "SUPPORT", null)));
    }

    @Test
    void unrestrictedLobbyIgnoresRankLimits() {
        LobbyObject lobby = lobby(300, 600, true);
        assertTrue(QueueMatcher.matches(lobby, profile(1, "PC", "EU", "DE", 999, "SUPPORT", null)));
    }

    private LobbyObject lobby() {
        return lobby(null, null, false);
    }

    private LobbyObject lobby(Integer customRankMin, Integer customRankMax, boolean unrestricted) {
        Timestamp created = Timestamp.from(now.minus(1, ChronoUnit.HOURS));
        return new LobbyObject(1, 1, 99, 0, 0, null, 5, "PC", "EU", "DE", 100, 800,
                customRankMin, customRankMax, unrestricted, "SUPPORT", LobbyStatus.OPEN,
                true, false, null, null, created, null);
    }

    private SearchProfile profile(int userId, String platform, String region, String language,
                                  int rank, String role, Instant lastInvited) {
        return new SearchProfile(userId, userId, 1, platform, region, language, rank, role, true,
                lastInvited == null ? null : Timestamp.from(lastInvited));
    }
}
