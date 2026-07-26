package de.flolang.matchyourgame.manager.gameapi;

import de.flolang.matchyourgame.database.game.GameObject;
import de.flolang.matchyourgame.database.game.GameRepository;
import de.flolang.matchyourgame.database.game.GameStatDefinition;
import de.flolang.matchyourgame.database.game.GameStatRepository;
import de.flolang.matchyourgame.database.gameapi.GameApiRepository;
import de.flolang.matchyourgame.database.lobby.LobbyObject;
import de.flolang.matchyourgame.database.lobby.LobbyRepository;
import de.flolang.matchyourgame.database.match.MatchRepository;
import de.flolang.matchyourgame.database.profile.GameProfile;
import de.flolang.matchyourgame.database.profile.GameProfileRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.gameapi.GameApiException;
import de.flolang.matchyourgame.gameapi.GameApiProvider;
import de.flolang.matchyourgame.gameapi.GameApiRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;

public final class GameApiService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameApiService.class);
    private final Map<Integer, ImportSession> imports = new ConcurrentHashMap<>();
    private final Map<String, PendingAccountLogin> accountLogins = new ConcurrentHashMap<>();
    private final java.security.SecureRandom secureRandom = new java.security.SecureRandom();

    public AccountLogin beginAccountLogin(int userId, int gameId, String platform) throws GameApiException {
        GameProfile profile = GameProfileRepository.get(userId, gameId, platform);
        if (profile == null) throw new GameApiException("Dieses Spielprofil existiert nicht.");
        String providerId = GameApiRepository.providerId(gameId);
        GameApiProvider provider = GameApiRegistry.get(providerId);
        if (provider == null || !provider.supportsAccountLogin())
            throw new GameApiException("Für dieses Game ist kein Account-Login verfügbar.");
        String redirectUri = de.flolang.matchyourgame.config.ConfigManager.getString(
                "GameAPIs.OAuth.RedirectUri", "");
        if (redirectUri.isBlank()) throw new GameApiException("Die öffentliche OAuth-Callback-URL fehlt.");
        cleanupAccountLogins();
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        String state = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        accountLogins.put(state, new PendingAccountLogin(userId, gameId, platform, provider.id(),
                Instant.now().plus(Duration.ofMinutes(10))));
        return new AccountLogin(provider.displayName(), provider.accountLoginUrl(state, redirectUri));
    }

    public CompletedAccountLogin completeAccountLogin(String state, String code) throws GameApiException {
        cleanupAccountLogins();
        PendingAccountLogin pending = state == null ? null : accountLogins.remove(state);
        if (pending == null || pending.expiresAt().isBefore(Instant.now()))
            throw new GameApiException("Dieser Login-Link ist ungültig oder abgelaufen.");
        if (code == null || code.isBlank()) throw new GameApiException("Riot hat keinen Login-Code zurückgegeben.");
        GameProfile profile = GameProfileRepository.get(
                pending.userId(), pending.gameId(), pending.platform());
        GameApiProvider provider = GameApiRegistry.get(pending.providerId());
        if (profile == null || provider == null)
            throw new GameApiException("Das ausgewählte Spielprofil ist nicht mehr verfügbar.");
        String redirectUri = de.flolang.matchyourgame.config.ConfigManager.getString(
                "GameAPIs.OAuth.RedirectUri", "");
        GameApiProvider.LinkedAccount account = provider.completeAccountLogin(code, redirectUri,
                new GameApiProvider.AccountLinkRequest(null, profile.region(), profile.platform()));
        if (!GameApiRepository.linkAccount(pending.userId(), pending.gameId(), pending.platform(),
                provider.id(), account, GameApiProvider.AccountVerification.VERIFIED_LOGIN))
            throw new GameApiException("Der Account konnte nicht gespeichert werden.");
        autoMapRanks(pending.gameId());
        Integer rank = null;
        String rankSyncError = null;
        GameObject game = GameRepository.get(pending.gameId());
        if (game != null && game.isSkillbased()) {
            try {
                String externalRank = provider.rank(account);
                rank = GameApiRepository.rankOrderForApiValue(pending.gameId(), provider.id(), externalRank);
                if (rank != null) GameApiRepository.updateProfileRank(pending.userId(), pending.gameId(),
                        pending.platform(), rank);
            } catch (GameApiException exception) {
                rankSyncError = exception.getMessage();
                LOGGER.warn("Account linked, but initial API rank could not be synchronized for user {}: {}",
                        pending.userId(), exception.getMessage());
            }
        }
        return new CompletedAccountLogin(pending.userId(), pending.gameId(), pending.platform(),
                provider.displayName(), account.displayName(), rank, rankSyncError);
    }

    private void cleanupAccountLogins() {
        Instant now = Instant.now();
        accountLogins.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    public LinkResult linkProfile(int userId, int gameId, String platform, String accountName,
                                  String routing, String shard) throws GameApiException {
        GameProfile profile = GameProfileRepository.get(userId, gameId, platform);
        if (profile == null) throw new GameApiException("Dieses Spielprofil existiert nicht.");
        String providerId = GameApiRepository.providerId(gameId);
        GameApiProvider provider = GameApiRegistry.get(providerId);
        if (provider == null) throw new GameApiException("Für dieses Game ist keine API aktiviert.");
        if (!provider.supportsManualAccountLink())
            throw new GameApiException("Dieser API-Provider erlaubt keine manuelle Account-Verknüpfung.");
        autoMapRanks(gameId);
        GameApiProvider.LinkedAccount account = provider.linkAccount(
                new GameApiProvider.AccountLinkRequest(accountName, routing, shard));
        if (!GameApiRepository.linkAccount(userId, gameId, platform, provider.id(), account,
                GameApiProvider.AccountVerification.RIOT_ID_FALLBACK))
            throw new GameApiException("Der Account konnte nicht gespeichert werden.");
        Integer rank = null;
        String rankSyncError = null;
        GameObject game = GameRepository.get(gameId);
        if (game != null && game.isSkillbased()) {
            try {
                String externalRank = provider.rank(account);
                rank = GameApiRepository.rankOrderForApiValue(gameId, provider.id(), externalRank);
                if (rank != null) GameApiRepository.updateProfileRank(userId, gameId, platform, rank);
            } catch (GameApiException exception) {
                rankSyncError = exception.getMessage();
                LOGGER.warn("Account linked, but initial API rank could not be synchronized for user {}: {}",
                        userId, exception.getMessage());
            }
        }
        return new LinkResult(provider.displayName(), account.displayName(), rank, rankSyncError);
    }

    public int autoMapRanks(int gameId) {
        GameApiProvider provider = GameApiRegistry.get(GameApiRepository.providerId(gameId));
        if (provider == null || provider.rankValues().isEmpty()) return 0;
        Map<String, GameApiProvider.RankValue> apiRanks = new LinkedHashMap<>();
        for (GameApiProvider.RankValue rank : provider.rankValues())
            apiRanks.putIfAbsent(normalizeRankName(rank.label()), rank);
        int mapped = 0;
        for (de.flolang.matchyourgame.database.game.GameOption rank :
                de.flolang.matchyourgame.database.game.GameOptionRepository.get(
                        gameId, de.flolang.matchyourgame.database.game.GameOption.Type.RANK)) {
            GameApiRepository.RankMapping existing = GameApiRepository.getRankMapping(rank.id());
            if (existing != null && provider.id().equalsIgnoreCase(existing.providerId())) continue;
            GameApiProvider.RankValue apiRank = apiRanks.get(normalizeRankName(rank.name()));
            if (apiRank != null && GameApiRepository.setRankMapping(
                    rank.id(), provider.id(), apiRank.key())) mapped++;
        }
        return mapped;
    }

    public int autoMapAllRanks() {
        int mapped = 0;
        for (GameObject game : GameRepository.getAllGames()) mapped += autoMapRanks(game.getId());
        return mapped;
    }

    static String normalizeRankName(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[_\\-/]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public ImportSession startImport(int lobbyId, int hostId) throws GameApiException {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null || lobby.getLeaderID() != hostId)
            throw new GameApiException("Nur der Host kann Matches importieren.");
        String providerId = GameApiRepository.providerId(lobby.getGameID());
        GameApiProvider provider = GameApiRegistry.get(providerId);
        if (provider == null) throw new GameApiException("Für dieses Game ist keine API aktiviert.");

        List<Integer> members = LobbyRepository.allMemberIds(lobbyId);
        Map<Integer, GameApiRepository.LinkedProfileAccount> linked = linkedAccounts(lobby, members);
        if (linked.isEmpty())
            throw new GameApiException("Mindestens ein Lobby-Mitglied muss für dieses Spielprofil einen API-Account verknüpft haben.");

        Instant from = (lobby.getVoiceCreatedAt() != null ? lobby.getVoiceCreatedAt() : lobby.getCreatedAt()).toInstant();
        Instant until = (lobby.getClosedAt() == null ? java.sql.Timestamp.from(Instant.now()) : lobby.getClosedAt()).toInstant();
        Map<String, GameApiProvider.ExternalMatch> matches = new LinkedHashMap<>();
        for (GameApiRepository.LinkedProfileAccount account : linked.values()) {
            for (GameApiProvider.ExternalMatch match : provider.findMatches(account.account(), from, until)) {
                if (!GameApiRepository.imported(provider.id(), match.externalId()))
                    matches.putIfAbsent(match.externalId(), match);
            }
        }
        if (matches.isEmpty()) throw new GameApiException("Im Zeitraum der Lobby wurden keine neuen Matches gefunden.");

        Set<String> linkedExternalIds = linked.values().stream()
                .map(GameApiRepository.LinkedProfileAccount::externalId).collect(java.util.stream.Collectors.toSet());
        Map<String, ExternalCandidate> candidates = collectCandidates(matches.values(), linkedExternalIds);
        Map<Integer, String> mappings = new LinkedHashMap<>();
        List<MappingRequest> requests = new ArrayList<>();
        for (int memberId : members) {
            GameApiRepository.LinkedProfileAccount account = linked.get(memberId);
            if (account != null && candidates.containsKey(account.externalId())) {
                mappings.put(memberId, account.externalId());
            } else {
                String username = UserController.get(memberId) == null ? String.valueOf(memberId)
                        : UserController.get(memberId).getUsername();
                requests.add(new MappingRequest(memberId, username, account != null,
                        account == null ? null : account.displayName()));
            }
        }
        long freeCandidates = candidates.keySet().stream().filter(id -> !mappings.containsValue(id)).count();
        if (freeCandidates < requests.size())
            throw new GameApiException("Die API-Lobby enthält nicht genügend eindeutig zuordenbare Spieler.");
        ImportSession session = new ImportSession(lobbyId, hostId, provider, List.copyOf(matches.values()),
                List.copyOf(candidates.values()), mappings, requests);
        imports.put(lobbyId, session);
        if (requests.isEmpty()) completeImport(session);
        return session;
    }

    public ImportSession getImport(int lobbyId, int hostId) {
        ImportSession session = imports.get(lobbyId);
        return session != null && session.hostId() == hostId ? session : null;
    }

    public boolean mapNext(int lobbyId, int hostId, String externalId) throws GameApiException {
        ImportSession session = getImport(lobbyId, hostId);
        MappingRequest request = session == null ? null : session.nextRequest();
        if (request == null) return false;
        boolean valid = session.candidates().stream().anyMatch(candidate -> candidate.externalId().equals(externalId));
        if (!valid || session.mappings().containsValue(externalId)) return false;
        session.mappings().put(request.userId(), externalId);
        session.advance();
        if (session.nextRequest() == null) completeImport(session);
        return true;
    }

    private void completeImport(ImportSession session) throws GameApiException {
        int imported = 0;
        for (GameApiProvider.ExternalMatch external : session.matches()) {
            if (GameApiRepository.imported(session.provider().id(), external.externalId())) continue;
            Map<Integer, GameApiProvider.ExternalPlayer> participants = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> mapping : session.mappings().entrySet()) {
                external.players().stream().filter(player -> player.externalId().equals(mapping.getValue()))
                        .findFirst().ifPresent(player -> participants.put(mapping.getKey(), player));
            }
            if (participants.size() < 2) continue;
            int matchId = MatchRepository.create(session.lobbyId());
            int teamId = matchId == 0 ? 0 : MatchRepository.addTeam(matchId, "API");
            if (teamId == 0 || !MatchRepository.addParticipants(matchId, teamId, new ArrayList<>(participants.keySet())))
                throw new GameApiException("Ein importiertes Match konnte nicht gespeichert werden.");

            LobbyObject lobby = LobbyRepository.get(session.lobbyId());
            for (GameStatDefinition definition : GameStatRepository.getEffectiveForGame(lobby.getGameID())) {
                GameApiRepository.StatisticMapping mapping = GameApiRepository.getStatisticMapping(definition.id());
                if (mapping == null || !session.provider().id().equalsIgnoreCase(mapping.providerId())) continue;
                if (definition.scope() == GameStatDefinition.Scope.PLAYER) {
                    participants.forEach((userId, player) ->
                            saveMapped(matchId, definition, userId, null, player.statistics().get(mapping.fieldKey())));
                } else {
                    String value = external.matchStatistics().get(mapping.fieldKey());
                    if (value == null) {
                        String teamKey = participants.values().stream().map(GameApiProvider.ExternalPlayer::teamKey)
                                .filter(key -> key != null && !key.isBlank()).findFirst().orElse("");
                        value = external.teamStatistics().getOrDefault(teamKey, Map.of()).get(mapping.fieldKey());
                    }
                    saveMapped(matchId, definition, null, teamId, value);
                }
            }
            MatchRepository.markReady(matchId);
            if (!GameApiRepository.markImported(matchId, session.provider().id(), external.externalId()))
                LOGGER.warn("Imported match {} could not be marked with external id {}", matchId, external.externalId());
            imported++;
        }
        imports.remove(session.lobbyId());
        session.complete(imported);
        if (imported == 0) throw new GameApiException("Die gefundenen Matches enthielten weniger als zwei zugeordnete Lobby-Spieler.");
        java.util.concurrent.CompletableFuture.runAsync(() -> refreshLobbyRanks(session));
    }

    private void refreshLobbyRanks(ImportSession session) {
        LobbyObject lobby = LobbyRepository.get(session.lobbyId());
        GameObject game = lobby == null ? null : GameRepository.get(lobby.getGameID());
        if (game == null || !game.isSkillbased()) return;
        Map<Integer, GameApiRepository.LinkedProfileAccount> accounts = linkedAccounts(
                lobby, LobbyRepository.allMemberIds(lobby.getId()));
        for (GameApiRepository.LinkedProfileAccount account : accounts.values()) {
            try {
                String externalRank = session.provider().rank(account.account());
                Integer rankValue = GameApiRepository.rankOrderForApiValue(
                        lobby.getGameID(), session.provider().id(), externalRank);
                if (rankValue != null)
                    GameApiRepository.updateProfileRank(account.userId(), account.gameId(),
                            account.platform(), rankValue);
            } catch (GameApiException exception) {
                LOGGER.warn("Could not refresh API rank for user {} after lobby {}",
                        account.userId(), lobby.getId(), exception);
            }
        }
    }

    private static void saveMapped(int matchId, GameStatDefinition definition, Integer userId,
                                   Integer teamId, String value) {
        if (value != null && !value.isBlank())
            MatchRepository.saveStat(matchId, definition.id(), userId, teamId, value);
    }

    private static Map<Integer, GameApiRepository.LinkedProfileAccount> linkedAccounts(
            LobbyObject lobby, List<Integer> memberIds) {
        Map<Integer, GameApiRepository.LinkedProfileAccount> result = new LinkedHashMap<>();
        for (GameApiRepository.LinkedProfileAccount account : GameApiRepository.getAccountsForGame(lobby.getGameID())) {
            if (!memberIds.contains(account.userId())) continue;
            GameApiProvider provider = GameApiRegistry.get(account.providerId());
            if (provider == null || !provider.acceptsAccountVerification(account.verification())) continue;
            if (!"ANY".equalsIgnoreCase(lobby.getPlatform())
                    && !lobby.getPlatform().equalsIgnoreCase(account.platform())) continue;
            result.putIfAbsent(account.userId(), account);
        }
        return result;
    }

    private static Map<String, ExternalCandidate> collectCandidates(
            java.util.Collection<GameApiProvider.ExternalMatch> matches, Set<String> linkedIds) {
        Map<String, ExternalCandidate> result = new LinkedHashMap<>();
        for (GameApiProvider.ExternalMatch match : matches) {
            Set<String> linkedTeams = match.players().stream().filter(player -> linkedIds.contains(player.externalId()))
                    .map(GameApiProvider.ExternalPlayer::teamKey).filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            match.players().stream().filter(player -> linkedTeams.isEmpty() || linkedTeams.contains(player.teamKey()))
                    .forEach(player -> result.putIfAbsent(player.externalId(),
                            new ExternalCandidate(player.externalId(), player.displayName())));
        }
        return result.values().stream().sorted(Comparator.comparing(ExternalCandidate::displayName))
                .collect(LinkedHashMap::new, (map, value) -> map.put(value.externalId(), value), Map::putAll);
    }

    public record LinkResult(String providerName, String accountName, Integer rankValue,
                             String rankSyncError) {}
    public record AccountLogin(String providerName, String authorizationUrl) {}
    public record CompletedAccountLogin(int userId, int gameId, String platform, String providerName,
                                        String accountName, Integer rankValue, String rankSyncError) {}
    private record PendingAccountLogin(int userId, int gameId, String platform, String providerId,
                                       Instant expiresAt) {}
    public record ExternalCandidate(String externalId, String displayName) {}
    public record MappingRequest(int userId, String username, boolean linkedAccountMissing,
                                 String linkedAccountName) {}

    public static final class ImportSession {
        private final int lobbyId, hostId;
        private final GameApiProvider provider;
        private final List<GameApiProvider.ExternalMatch> matches;
        private final List<ExternalCandidate> candidates;
        private final Map<Integer, String> mappings;
        private final List<MappingRequest> requests;
        private int cursor;
        private int importedCount = -1;

        ImportSession(int lobbyId, int hostId, GameApiProvider provider,
                      List<GameApiProvider.ExternalMatch> matches, List<ExternalCandidate> candidates,
                      Map<Integer, String> mappings, List<MappingRequest> requests) {
            this.lobbyId = lobbyId; this.hostId = hostId; this.provider = provider; this.matches = matches;
            this.candidates = candidates; this.mappings = mappings; this.requests = requests;
        }
        public int lobbyId() { return lobbyId; }
        public int hostId() { return hostId; }
        public GameApiProvider provider() { return provider; }
        public List<GameApiProvider.ExternalMatch> matches() { return matches; }
        public List<ExternalCandidate> candidates() { return candidates; }
        public Map<Integer, String> mappings() { return mappings; }
        public MappingRequest nextRequest() { return cursor < requests.size() ? requests.get(cursor) : null; }
        public int importedCount() { return importedCount; }
        void advance() { cursor++; }
        void complete(int count) { importedCount = count; }
    }
}
