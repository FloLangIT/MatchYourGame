package de.flolang.matchyourgame.gameapi.valorant;

import de.flolang.matchyourgame.config.ConfigManager;
import de.flolang.matchyourgame.gameapi.GameApiException;
import de.flolang.matchyourgame.gameapi.GameApiProvider;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Official Riot VALORANT API adapter with Riot Sign On account verification. */
public final class ValorantApiProvider implements GameApiProvider {
    private static final List<StatisticField> FIELDS = List.of(
            field("player.score", "Spieler: Score", StatisticField.Scope.PLAYER, StatisticField.ValueType.INTEGER),
            field("player.kills", "Spieler: Kills", StatisticField.Scope.PLAYER, StatisticField.ValueType.INTEGER),
            field("player.deaths", "Spieler: Deaths", StatisticField.Scope.PLAYER, StatisticField.ValueType.INTEGER),
            field("player.assists", "Spieler: Assists", StatisticField.Scope.PLAYER, StatisticField.ValueType.INTEGER),
            field("player.headshots", "Spieler: Headshots", StatisticField.Scope.PLAYER, StatisticField.ValueType.INTEGER),
            field("player.bodyshots", "Spieler: Bodyshots", StatisticField.Scope.PLAYER, StatisticField.ValueType.INTEGER),
            field("player.legshots", "Spieler: Legshots", StatisticField.Scope.PLAYER, StatisticField.ValueType.INTEGER),
            field("player.competitiveTier", "Spieler: Competitive Tier", StatisticField.Scope.PLAYER, StatisticField.ValueType.INTEGER),
            field("player.characterId", "Spieler: Agent-ID", StatisticField.Scope.PLAYER, StatisticField.ValueType.TEXT),
            field("team.roundsWon", "Team: Runden gewonnen", StatisticField.Scope.TEAM, StatisticField.ValueType.INTEGER),
            field("team.roundsPlayed", "Team: Runden gespielt", StatisticField.Scope.TEAM, StatisticField.ValueType.INTEGER),
            field("team.won", "Team: Gewonnen", StatisticField.Scope.TEAM, StatisticField.ValueType.BOOLEAN),
            field("team.opponentRoundsWon", "Gegner: Runden gewonnen", StatisticField.Scope.TEAM, StatisticField.ValueType.INTEGER),
            field("team.opponentRoundsPlayed", "Gegner: Runden gespielt", StatisticField.Scope.TEAM, StatisticField.ValueType.INTEGER),
            field("team.opponentWon", "Gegner: Gewonnen", StatisticField.Scope.TEAM, StatisticField.ValueType.BOOLEAN),
            field("match.mapId", "Match: Map-ID", StatisticField.Scope.MATCH, StatisticField.ValueType.TEXT),
            field("match.queueId", "Match: Queue", StatisticField.Scope.MATCH, StatisticField.ValueType.TEXT),
            field("match.gameLengthMillis", "Match: Dauer (ms)", StatisticField.Scope.MATCH, StatisticField.ValueType.INTEGER)
    );
    private static final List<RankValue> RANKS = List.of(
            rank(3, "Iron 1"), rank(4, "Iron 2"), rank(5, "Iron 3"),
            rank(6, "Bronze 1"), rank(7, "Bronze 2"), rank(8, "Bronze 3"),
            rank(9, "Silver 1"), rank(10, "Silver 2"), rank(11, "Silver 3"),
            rank(12, "Gold 1"), rank(13, "Gold 2"), rank(14, "Gold 3"),
            rank(15, "Platinum 1"), rank(16, "Platinum 2"), rank(17, "Platinum 3"),
            rank(18, "Diamond 1"), rank(19, "Diamond 2"), rank(20, "Diamond 3"),
            rank(21, "Ascendant 1"), rank(22, "Ascendant 2"), rank(23, "Ascendant 3"),
            rank(24, "Immortal 1"), rank(25, "Immortal 2"), rank(26, "Immortal 3"),
            rank(27, "Radiant")
    );

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, ExternalMatch> matchCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Override public String id() { return "VALORANT"; }
    @Override public String displayName() { return "Valorant (Riot API)"; }
    @Override public List<StatisticField> statisticFields() { return FIELDS; }
    @Override public List<RankValue> rankValues() { return RANKS; }
    @Override public boolean supportsAccountLogin() { return true; }
    @Override public boolean accountLoginConfigured() {
        return !ConfigManager.getString("GameAPIs.Valorant.RsoClientId", "").isBlank()
                && !ConfigManager.getString("GameAPIs.Valorant.RsoClientSecret", "").isBlank();
    }
    @Override public boolean supportsManualAccountLink() { return false; }
    @Override public boolean acceptsAccountVerification(AccountVerification verification) {
        return verification == AccountVerification.VERIFIED_LOGIN;
    }

    @Override
    public String accountLoginUrl(String state, String redirectUri) throws GameApiException {
        String clientId = ConfigManager.getString("GameAPIs.Valorant.RsoClientId", "");
        if (clientId.isBlank() || redirectUri == null || redirectUri.isBlank())
            throw new GameApiException("Riot RSO ist noch nicht konfiguriert. Dafür wird ein freigeschalteter RSO-Client benötigt.");
        return "https://auth.riotgames.com/authorize?client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_type=code&scope=" + encode("openid offline_access")
                + "&state=" + encode(state);
    }

    @Override
    public LinkedAccount completeAccountLogin(String code, String redirectUri,
                                              AccountLinkRequest context) throws GameApiException {
        String clientId = ConfigManager.getString("GameAPIs.Valorant.RsoClientId", "");
        String clientSecret = ConfigManager.getString("GameAPIs.Valorant.RsoClientSecret", "");
        if (clientId.isBlank() || clientSecret.isBlank())
            throw new GameApiException("Riot RSO ist noch nicht vollständig konfiguriert.");
        String form = "grant_type=authorization_code&code=" + encode(code)
                + "&redirect_uri=" + encode(redirectUri);
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest tokenRequest = HttpRequest.newBuilder(URI.create("https://auth.riotgames.com/token"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(BodyPublishers.ofString(form)).build();
        try {
            HttpResponse<String> response = http.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2)
                throw new GameApiException("Riot Login konnte nicht bestätigt werden (HTTP "
                        + response.statusCode() + ").");
            String accessToken = DataObject.fromJson(response.body()).getString("access_token");
            String routing = normalizeRouting(ConfigManager.getString(
                    "GameAPIs.Valorant.RsoRouting", "europe"));
            DataObject account = getBearerObject("https://" + routing
                    + ".api.riotgames.com/riot/account/v1/accounts/me", accessToken);
            String name = account.getString("gameName", "");
            String tag = account.getString("tagLine", "");
            String requestedRegion = context == null ? null : context.routing();
            String requestedPlatform = context == null ? null : context.shard();
            return new LinkedAccount(account.getString("puuid"),
                    tag.isBlank() ? name : name + "#" + tag,
                    normalizeRouting(requestedRegion),
                    normalizeShard(requestedPlatform, requestedRegion));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GameApiException("Riot Login wurde unterbrochen.", exception);
        } catch (java.io.IOException | RuntimeException exception) {
            throw new GameApiException("Riot Login konnte nicht verarbeitet werden.", exception);
        }
    }

    @Override
    public LinkedAccount linkAccount(AccountLinkRequest request) throws GameApiException {
        String[] riotId = request.accountName() == null
                ? new String[0] : request.accountName().trim().split("#", 2);
        if (riotId.length != 2 || riotId[0].isBlank() || riotId[1].isBlank())
            throw new GameApiException("Die Riot-ID muss im Format Name#Tag angegeben werden.");
        String routing = normalizeRouting(request.routing());
        DataObject account = getObject("https://" + routing
                + ".api.riotgames.com/riot/account/v1/accounts/by-riot-id/"
                + encode(riotId[0]) + "/" + encode(riotId[1]));
        return new LinkedAccount(account.getString("puuid"),
                account.getString("gameName", riotId[0]) + "#"
                        + account.getString("tagLine", riotId[1]),
                routing, normalizeShard(request.shard(), routing));
    }

    @Override
    public List<ExternalMatch> findMatches(LinkedAccount account, Instant from, Instant until)
            throws GameApiException {
        String shard = normalizeShard(account.shard(), account.routing());
        String url = "https://" + shard + ".api.riotgames.com/val/match/v1/matchlists/by-puuid/"
                + encode(account.externalId()) + "?startIndex=0&endIndex=100";
        DataArray history = getObject(url).getArray("history");
        List<ExternalMatch> result = new ArrayList<>();
        for (int i = 0; i < history.length(); i++) {
            DataObject item = history.getObject(i);
            long startMillis = item.getLong("gameStartTimeMillis", 0);
            if (startMillis > 0
                    && (startMillis < from.toEpochMilli() || startMillis > until.toEpochMilli())) continue;
            String matchId = item.getString("matchId");
            ExternalMatch match = matchCache.get(matchId);
            if (match == null) {
                match = match(account, matchId);
                matchCache.put(matchId, match);
            }
            if (!match.startedAt().isBefore(from) && !match.startedAt().isAfter(until))
                result.add(match);
        }
        result.sort(Comparator.comparing(ExternalMatch::startedAt));
        return result;
    }

    @Override
    public String rank(LinkedAccount account) throws GameApiException {
        Instant until = Instant.now();
        List<ExternalMatch> matches = findMatches(account, until.minus(Duration.ofDays(90)), until);
        for (int i = matches.size() - 1; i >= 0; i--) {
            ExternalMatch match = matches.get(i);
            if (!"competitive".equalsIgnoreCase(match.matchStatistics().get("match.queueId"))) continue;
            for (ExternalPlayer player : match.players()) {
                if (!player.externalId().equals(account.externalId())) continue;
                try {
                    int tier = Integer.parseInt(player.statistics().get("player.competitiveTier"));
                    return tier <= 2 ? null : String.valueOf(tier);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private ExternalMatch match(LinkedAccount account, String matchId) throws GameApiException {
        String shard = normalizeShard(account.shard(), account.routing());
        DataObject root = getObject("https://" + shard
                + ".api.riotgames.com/val/match/v1/matches/" + encode(matchId));
        DataObject info = root.getObject("matchInfo");
        long start = info.getLong("gameStartMillis", 0);
        long length = info.getLong("gameLengthMillis", 0);
        Map<String, String> matchStats = new LinkedHashMap<>();
        matchStats.put("match.mapId", info.getString("mapId", ""));
        matchStats.put("match.queueId", info.getString("queueId", ""));
        matchStats.put("match.gameLengthMillis", String.valueOf(length));

        List<ExternalPlayer> players = new ArrayList<>();
        DataArray playerArray = root.getArray("players");
        for (int i = 0; i < playerArray.length(); i++) {
            DataObject player = playerArray.getObject(i);
            DataObject stats = player.getObject("stats");
            Map<String, String> values = new LinkedHashMap<>();
            put(values, "player.score", stats.getInt("score", 0));
            put(values, "player.kills", stats.getInt("kills", 0));
            put(values, "player.deaths", stats.getInt("deaths", 0));
            put(values, "player.assists", stats.getInt("assists", 0));
            put(values, "player.headshots", stats.getInt("headshots", 0));
            put(values, "player.bodyshots", stats.getInt("bodyshots", 0));
            put(values, "player.legshots", stats.getInt("legshots", 0));
            put(values, "player.competitiveTier", player.getInt("competitiveTier", 0));
            values.put("player.characterId", player.getString("characterId", ""));
            String name = player.getString("gameName", "");
            String tag = player.getString("tagLine", "");
            players.add(new ExternalPlayer(player.getString("puuid"),
                    tag.isBlank() ? name : name + "#" + tag,
                    player.getString("teamId", ""), values));
        }

        Map<String, Map<String, String>> teams = new HashMap<>();
        DataArray teamArray = root.getArray("teams");
        for (int i = 0; i < teamArray.length(); i++) {
            DataObject team = teamArray.getObject(i);
            Map<String, String> values = new LinkedHashMap<>();
            put(values, "team.roundsWon", team.getInt("roundsWon", 0));
            put(values, "team.roundsPlayed", team.getInt("roundsPlayed", 0));
            values.put("team.won", String.valueOf(team.getBoolean("won", false)));
            teams.put(team.getString("teamId", ""), values);
        }
        for (Map.Entry<String, Map<String, String>> team : teams.entrySet()) {
            Map<String, String> opponent = teams.entrySet().stream()
                    .filter(candidate -> !candidate.getKey().equals(team.getKey()))
                    .map(Map.Entry::getValue).findFirst().orElse(Map.of());
            team.getValue().put("team.opponentRoundsWon",
                    opponent.getOrDefault("team.roundsWon", "0"));
            team.getValue().put("team.opponentRoundsPlayed",
                    opponent.getOrDefault("team.roundsPlayed", "0"));
            team.getValue().put("team.opponentWon",
                    opponent.getOrDefault("team.won", "false"));
        }
        return new ExternalMatch(matchId, Instant.ofEpochMilli(start),
                Instant.ofEpochMilli(start + Math.max(0, length)),
                players, teams, matchStats);
    }

    private DataObject getObject(String url) throws GameApiException {
        String key = ConfigManager.getString("GameAPIs.Valorant.ApiKey", "");
        if (key.isBlank()) throw new GameApiException("Der Riot-API-Key ist nicht konfiguriert.");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("X-Riot-Token", key).header("Accept", "application/json").GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404)
                throw new GameApiException("Riot-Account oder Match wurde nicht gefunden.");
            if (response.statusCode() == 401 || response.statusCode() == 403)
                throw new GameApiException("Der Riot-API-Key ist ungültig, abgelaufen oder nicht für Valorant freigeschaltet.");
            if (response.statusCode() == 429)
                throw new GameApiException("Das Riot-API-Limit wurde erreicht. Bitte später erneut versuchen.");
            if (response.statusCode() / 100 != 2)
                throw new GameApiException("Riot API antwortete mit HTTP " + response.statusCode() + ".");
            return DataObject.fromJson(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GameApiException("Riot-API-Abfrage wurde unterbrochen.", exception);
        } catch (java.io.IOException | RuntimeException exception) {
            throw new GameApiException("Riot API konnte nicht gelesen werden.", exception);
        }
    }

    private DataObject getBearerObject(String url, String accessToken) throws GameApiException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json").GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2)
                throw new GameApiException("Riot Account konnte nicht gelesen werden (HTTP "
                        + response.statusCode() + ").");
            return DataObject.fromJson(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GameApiException("Riot-Account-Abfrage wurde unterbrochen.", exception);
        } catch (java.io.IOException | RuntimeException exception) {
            throw new GameApiException("Riot Account konnte nicht gelesen werden.", exception);
        }
    }

    private static StatisticField field(String key, String label, StatisticField.Scope scope,
                                        StatisticField.ValueType type) {
        return new StatisticField(key, label, scope, type);
    }
    private static RankValue rank(int key, String label) {
        return new RankValue(String.valueOf(key), label);
    }
    private static void put(Map<String, String> map, String key, int value) {
        map.put(key, String.valueOf(value));
    }
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
    private static String normalizeRouting(String value) {
        String routing = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (List.of("europe", "americas", "asia").contains(routing)) return routing;
        if (routing.equals("na") || routing.contains("america")) return "americas";
        if (routing.equals("ap") || routing.equals("kr") || routing.contains("asia")
                || routing.contains("pacific")) return "asia";
        return "europe";
    }
    private static String normalizeShard(String value, String routing) {
        String shard = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (List.of("eu", "na", "ap", "kr", "latam", "br").contains(shard)) return shard;
        return switch (normalizeRouting(routing)) {
            case "americas" -> "na";
            case "asia" -> "ap";
            default -> "eu";
        };
    }
}
