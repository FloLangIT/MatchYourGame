package de.flolang.matchyourgame.manager.queue;

import de.flolang.matchyourgame.database.lobby.LobbyObject;
import de.flolang.matchyourgame.database.lobby.QueueCandidate;
import de.flolang.matchyourgame.database.lobby.SearchProfile;
import de.flolang.matchyourgame.database.game.GameController;
import de.flolang.matchyourgame.database.game.GameObject;
import de.flolang.matchyourgame.database.game.RankCompatibilityRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public final class QueueMatcher {
    private QueueMatcher() {}

    public static boolean matches(LobbyObject lobby, SearchProfile profile) {
        return lobby.getGameID() == profile.gameId()
                && compatible(lobby.getPlatform(), profile.platform())
                && compatible(lobby.getRegion(), profile.region())
                && compatible(lobby.getPreferredRole(), profile.preferredRole())
                && rankCompatible(lobby, profile);
    }

    public static List<QueueCandidate> rank(LobbyObject lobby, List<QueueCandidate> candidates, Instant now) {
        return candidates.stream().filter(candidate -> matches(lobby, candidate.profile()))
                .sorted(Comparator.comparingDouble((QueueCandidate candidate) -> score(lobby, candidate, now)).reversed()
                        .thenComparingInt(candidate -> candidate.profile().userId()))
                .toList();
    }

    public static double score(LobbyObject lobby, QueueCandidate candidate, Instant now) {
        SearchProfile profile = candidate.profile();
        long waitingHours = profile.lastInvitedAt() == null ? 24 * 30
                : Math.max(0, Duration.between(profile.lastInvitedAt().toInstant(), now).toHours());
        double waitingScore = Math.min(45, waitingHours / 16.0);
        double ratingScore = Math.max(0, Math.min(35, (candidate.averageRating() - 1) / 4 * 35));
        double fitScore = 5;
        if (sameSpecific(lobby.getPlatform(), profile.platform())) fitScore += 5;
        if (sameSpecific(lobby.getRegion(), profile.region())) fitScore += 4;
        if (sameSpecific(lobby.getPreferredRole(), profile.preferredRole())) fitScore += 3;
        return waitingScore + ratingScore + fitScore;
    }

    private static boolean compatible(String left, String right) {
        return any(left) || any(right) || left.equalsIgnoreCase(right);
    }

    private static boolean sameSpecific(String left, String right) {
        return !any(left) && !any(right) && left.equalsIgnoreCase(right);
    }

    private static boolean any(String value) {
        return value == null || value.isBlank() || value.equalsIgnoreCase("ANY");
    }

    private static boolean rankCompatible(LobbyObject lobby, SearchProfile profile) {
        if (!usesRankRules(lobby)) return true;
        if ((lobby.getCustomRankMin() != null && profile.rankValue() < lobby.getCustomRankMin())
                || (lobby.getCustomRankMax() != null && profile.rankValue() > lobby.getCustomRankMax())) return false;
        if (lobby.getRankMin() == -1 && lobby.getRankMax() == -1) return true;
        if (lobby.getRankMin() == lobby.getRankMax())
            return RankCompatibilityRepository.isCompatible(lobby.getGameID(), lobby.getRankMin(), profile.rankValue());
        return profile.rankValue() >= lobby.getRankMin() && profile.rankValue() <= lobby.getRankMax();
    }

    public static boolean usesRankRules(LobbyObject lobby) {
        if (lobby.isRankRulesUnrestricted()) return false;
        GameObject game = GameController.get(lobby.getGameID());
        return game == null || game.isSkillbased();
    }
}
