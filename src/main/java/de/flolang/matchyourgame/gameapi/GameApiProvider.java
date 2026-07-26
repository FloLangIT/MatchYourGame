package de.flolang.matchyourgame.gameapi;

import java.time.Instant;
import java.util.List;

/**
 * Adapter boundary for games with an external API. New games only need to
 * implement this interface and register the provider in {@link GameApiRegistry}.
 */
public interface GameApiProvider {
    String id();

    String displayName();

    List<StatisticField> statisticFields();

    default List<RankValue> rankValues() {
        return List.of();
    }

    LinkedAccount linkAccount(AccountLinkRequest request) throws GameApiException;

    default boolean supportsAccountLogin() {
        return false;
    }

    default boolean accountLoginConfigured() {
        return supportsAccountLogin();
    }

    default boolean supportsManualAccountLink() {
        return true;
    }

    default boolean acceptsAccountVerification(AccountVerification verification) {
        return true;
    }

    default String accountLoginUrl(String state, String redirectUri) throws GameApiException {
        throw new GameApiException("Dieser API-Provider unterstützt keinen Account-Login.");
    }

    default LinkedAccount completeAccountLogin(String code, String redirectUri) throws GameApiException {
        throw new GameApiException("Dieser API-Provider unterstützt keinen Account-Login.");
    }

    default LinkedAccount completeAccountLogin(String code, String redirectUri,
                                               AccountLinkRequest context) throws GameApiException {
        return completeAccountLogin(code, redirectUri);
    }

    List<ExternalMatch> findMatches(LinkedAccount account, Instant from, Instant until)
            throws GameApiException;

    default String rank(LinkedAccount account) throws GameApiException {
        return null;
    }

    record AccountLinkRequest(String accountName, String routing, String shard) {}

    record LinkedAccount(String externalId, String displayName, String routing, String shard) {}

    enum AccountVerification { VERIFIED_LOGIN, RIOT_ID_FALLBACK }

    record RankValue(String key, String label) {}

    record StatisticField(String key, String label, Scope scope, ValueType valueType) {
        public enum Scope { PLAYER, TEAM, MATCH }
        public enum ValueType { INTEGER, DECIMAL, BOOLEAN, TEXT }
    }

    record ExternalPlayer(String externalId, String displayName, String teamKey,
                          java.util.Map<String, String> statistics) {}

    record ExternalMatch(String externalId, Instant startedAt, Instant endedAt,
                         List<ExternalPlayer> players,
                         java.util.Map<String, java.util.Map<String, String>> teamStatistics,
                         java.util.Map<String, String> matchStatistics) {}
}
