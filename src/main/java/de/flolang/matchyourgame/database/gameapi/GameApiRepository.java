package de.flolang.matchyourgame.database.gameapi;

import de.flolang.matchyourgame.database.Database;
import de.flolang.matchyourgame.database.game.GameObject;
import de.flolang.matchyourgame.database.game.GameOption;
import de.flolang.matchyourgame.database.game.GameOptionRepository;
import de.flolang.matchyourgame.database.game.GameRepository;
import de.flolang.matchyourgame.gameapi.GameApiProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class GameApiRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameApiRepository.class);

    private GameApiRepository() {}

    public static void init() {
        try (Connection conn = Database.getConnection(); Statement statement = conn.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS game_api_config (" +
                    "game_id BIGINT PRIMARY KEY,provider_id VARCHAR(40) NOT NULL,enabled BOOLEAN NOT NULL DEFAULT TRUE," +
                    "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                    "FOREIGN KEY (game_id) REFERENCES game(id) ON DELETE CASCADE)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS game_profile_api_account (" +
                    "user_id BIGINT NOT NULL,game_id BIGINT NOT NULL,platform VARCHAR(40) NOT NULL," +
                    "provider_id VARCHAR(40) NOT NULL,external_id VARCHAR(255) NOT NULL,display_name VARCHAR(255) NOT NULL," +
                    "routing VARCHAR(40) NOT NULL,shard VARCHAR(40) NOT NULL," +
                    "verification_method VARCHAR(24) NOT NULL DEFAULT 'RIOT_ID_FALLBACK'," +
                    "linked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "PRIMARY KEY (user_id,game_id,platform)," +
                    "FOREIGN KEY (user_id,game_id,platform) REFERENCES game_profile(user_id,game_id,platform) ON DELETE CASCADE)");
            ensureAccountVerificationColumn(conn);
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS game_stat_api_mapping (" +
                    "stat_definition_id BIGINT PRIMARY KEY,provider_id VARCHAR(40) NOT NULL,field_key VARCHAR(120) NOT NULL," +
                    "FOREIGN KEY (stat_definition_id) REFERENCES game_stat_definition(id) ON DELETE CASCADE)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS game_option_api_rank_mapping (" +
                    "rank_option_id BIGINT PRIMARY KEY,provider_id VARCHAR(40) NOT NULL," +
                    "external_rank_key VARCHAR(120) NOT NULL," +
                    "FOREIGN KEY (rank_option_id) REFERENCES game_option(id) ON DELETE CASCADE)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS game_api_match_import (" +
                    "match_id BIGINT PRIMARY KEY,provider_id VARCHAR(40) NOT NULL,external_match_id VARCHAR(255) NOT NULL," +
                    "imported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "UNIQUE KEY uq_game_api_external_match (provider_id,external_match_id)," +
                    "FOREIGN KEY (match_id) REFERENCES lobby_match(id) ON DELETE CASCADE)");
        } catch (SQLException e) {
            LOGGER.error("Could not initialize game API tables", e);
        }
    }

    public static String providerId(int gameId) {
        return effectiveConfiguration(gameId).providerId();
    }

    public static EffectiveApiConfiguration effectiveConfiguration(int gameId) {
        return effectiveConfiguration(gameId, gameId, new HashSet<>());
    }

    private static EffectiveApiConfiguration effectiveConfiguration(int gameId, int requestedGameId,
                                                                    Set<Integer> visited) {
        if (!visited.add(gameId)) return new EffectiveApiConfiguration(null, gameId, gameId != requestedGameId);
        DirectApiConfiguration direct = directConfiguration(gameId);
        if (direct != null) return new EffectiveApiConfiguration(
                direct.enabled() && !"NONE".equalsIgnoreCase(direct.providerId()) ? direct.providerId() : null,
                gameId, gameId != requestedGameId);
        GameObject game = GameRepository.get(gameId);
        GameObject parent = game == null ? null : game.getSubGameFrom();
        if (parent != null) return effectiveConfiguration(parent.getId(), requestedGameId, visited);
        return new EffectiveApiConfiguration(null, gameId, gameId != requestedGameId);
    }

    public static DirectApiConfiguration directConfiguration(int gameId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT provider_id,enabled FROM game_api_config WHERE game_id=?")) {
            ps.setInt(1, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new DirectApiConfiguration(rs.getString("provider_id"),
                        rs.getBoolean("enabled")) : null;
            }
        } catch (SQLException e) {
            LOGGER.error("Could not load API provider for game {}", gameId, e);
            return null;
        }
    }

    public static boolean configure(int gameId, String providerId) {
        try (Connection conn = Database.getConnection()) {
            if (providerId == null || providerId.isBlank() || "INHERIT".equalsIgnoreCase(providerId)) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM game_api_config WHERE game_id=?")) {
                    ps.setInt(1, gameId); ps.executeUpdate(); return true;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO game_api_config (game_id,provider_id,enabled) VALUES (?,?,?) " +
                            "ON DUPLICATE KEY UPDATE provider_id=VALUES(provider_id),enabled=VALUES(enabled)")) {
                boolean enabled = !"NONE".equalsIgnoreCase(providerId);
                ps.setInt(1, gameId); ps.setString(2, enabled ? providerId : "NONE");
                ps.setBoolean(3, enabled); ps.executeUpdate(); return true;
            }
        } catch (SQLException e) {
            LOGGER.error("Could not configure API provider for game {}", gameId, e);
            return false;
        }
    }

    public static boolean linkAccount(int userId, int gameId, String platform, String providerId,
                                      GameApiProvider.LinkedAccount account,
                                      GameApiProvider.AccountVerification verification) {
        String sql = "INSERT INTO game_profile_api_account " +
                "(user_id,game_id,platform,provider_id,external_id,display_name,routing,shard,verification_method) " +
                "VALUES (?,?,?,?,?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE provider_id=VALUES(provider_id),external_id=VALUES(external_id)," +
                "display_name=VALUES(display_name),routing=VALUES(routing),shard=VALUES(shard)," +
                "verification_method=VALUES(verification_method),linked_at=CURRENT_TIMESTAMP";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId); ps.setInt(2, gameId); ps.setString(3, platform); ps.setString(4, providerId);
            ps.setString(5, account.externalId()); ps.setString(6, account.displayName());
            ps.setString(7, account.routing()); ps.setString(8, account.shard());
            ps.setString(9, verification.name());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.error("Could not link API account to game profile", e);
            return false;
        }
    }

    public static LinkedProfileAccount getAccount(int userId, int gameId, String platform) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM game_profile_api_account WHERE user_id=? AND game_id=? AND platform=?")) {
            ps.setInt(1, userId); ps.setInt(2, gameId); ps.setString(3, platform);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? mapAccount(rs) : null; }
        } catch (SQLException e) {
            LOGGER.error("Could not load linked API account", e); return null;
        }
    }

    public static List<LinkedProfileAccount> getAccountsForGame(int gameId) {
        List<LinkedProfileAccount> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM game_profile_api_account WHERE game_id=? ORDER BY user_id,platform")) {
            ps.setInt(1, gameId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(mapAccount(rs)); }
        } catch (SQLException e) { LOGGER.error("Could not list linked API accounts", e); }
        return result;
    }

    public static boolean setStatisticMapping(int definitionId, String providerId, String fieldKey) {
        try (Connection conn = Database.getConnection()) {
            if (fieldKey == null || fieldKey.isBlank() || "NONE".equalsIgnoreCase(fieldKey)) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM game_stat_api_mapping WHERE stat_definition_id=?")) {
                    ps.setInt(1, definitionId); ps.executeUpdate(); return true;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO game_stat_api_mapping (stat_definition_id,provider_id,field_key) VALUES (?,?,?) " +
                            "ON DUPLICATE KEY UPDATE provider_id=VALUES(provider_id),field_key=VALUES(field_key)")) {
                ps.setInt(1, definitionId); ps.setString(2, providerId); ps.setString(3, fieldKey);
                ps.executeUpdate(); return true;
            }
        } catch (SQLException e) {
            LOGGER.error("Could not map statistic {}", definitionId, e); return false;
        }
    }

    public static boolean setRankMapping(int rankOptionId, String providerId, String externalRankKey) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO game_option_api_rank_mapping (rank_option_id,provider_id,external_rank_key) " +
                        "VALUES (?,?,?) ON DUPLICATE KEY UPDATE provider_id=VALUES(provider_id)," +
                        "external_rank_key=VALUES(external_rank_key)")) {
            ps.setInt(1, rankOptionId); ps.setString(2, providerId); ps.setString(3, externalRankKey);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.error("Could not map rank option {}", rankOptionId, e); return false;
        }
    }

    public static boolean removeRankMapping(int rankOptionId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM game_option_api_rank_mapping WHERE rank_option_id=?")) {
            ps.setInt(1, rankOptionId); ps.executeUpdate(); return true;
        } catch (SQLException e) {
            LOGGER.error("Could not remove API rank mapping {}", rankOptionId, e); return false;
        }
    }

    public static RankMapping getRankMapping(int rankOptionId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT provider_id,external_rank_key FROM game_option_api_rank_mapping WHERE rank_option_id=?")) {
            ps.setInt(1, rankOptionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new RankMapping(rankOptionId, rs.getString("provider_id"),
                        rs.getString("external_rank_key")) : null;
            }
        } catch (SQLException e) {
            LOGGER.error("Could not load API rank mapping {}", rankOptionId, e); return null;
        }
    }

    public static Integer rankOrderForApiValue(int gameId, String providerId, String externalRankKey) {
        if (providerId == null || externalRankKey == null) return null;
        for (GameOption rank : GameOptionRepository.get(gameId, GameOption.Type.RANK)) {
            RankMapping mapping = getRankMapping(rank.id());
            if (mapping != null && providerId.equalsIgnoreCase(mapping.providerId())
                    && externalRankKey.equals(mapping.externalRankKey())) return rank.sortOrder();
        }
        return null;
    }

    public static StatisticMapping getStatisticMapping(int definitionId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT provider_id,field_key FROM game_stat_api_mapping WHERE stat_definition_id=?")) {
            ps.setInt(1, definitionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new StatisticMapping(definitionId, rs.getString(1), rs.getString(2)) : null;
            }
        } catch (SQLException e) { LOGGER.error("Could not load statistic mapping", e); return null; }
    }

    public static boolean imported(String providerId, String externalMatchId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM game_api_match_import WHERE provider_id=? AND external_match_id=?")) {
            ps.setString(1, providerId); ps.setString(2, externalMatchId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { return false; }
    }

    public static boolean markImported(int matchId, String providerId, String externalMatchId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO game_api_match_import (match_id,provider_id,external_match_id) VALUES (?,?,?)")) {
            ps.setInt(1, matchId); ps.setString(2, providerId); ps.setString(3, externalMatchId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOGGER.error("Could not mark match {} as imported", matchId, e); return false;
        }
    }

    public static boolean updateProfileRank(int userId, int gameId, String platform, int rankValue) {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement profile = conn.prepareStatement(
                    "UPDATE game_profile SET rank_value=? WHERE user_id=? AND game_id=? AND platform=?");
                 PreparedStatement search = conn.prepareStatement(
                         "UPDATE search_profile SET rank_value=? WHERE user_id=? AND game_id=? AND platform=?")) {
                profile.setInt(1, rankValue); profile.setInt(2, userId); profile.setInt(3, gameId);
                profile.setString(4, platform);
                if (profile.executeUpdate() != 1) { conn.rollback(); return false; }
                search.setInt(1, rankValue); search.setInt(2, userId); search.setInt(3, gameId);
                search.setString(4, platform); search.executeUpdate();
                conn.commit(); return true;
            } catch (SQLException e) {
                conn.rollback(); throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOGGER.error("Could not update API rank", e); return false;
        }
    }

    private static LinkedProfileAccount mapAccount(ResultSet rs) throws SQLException {
        return new LinkedProfileAccount(rs.getInt("user_id"), rs.getInt("game_id"), rs.getString("platform"),
                rs.getString("provider_id"), rs.getString("external_id"), rs.getString("display_name"),
                rs.getString("routing"), rs.getString("shard"),
                verification(rs.getString("verification_method")), rs.getTimestamp("linked_at"));
    }

    public record LinkedProfileAccount(int userId, int gameId, String platform, String providerId,
                                       String externalId, String displayName, String routing, String shard,
                                       GameApiProvider.AccountVerification verification, Timestamp linkedAt) {
        public GameApiProvider.LinkedAccount account() {
            return new GameApiProvider.LinkedAccount(externalId, displayName, routing, shard);
        }
    }
    public record StatisticMapping(int definitionId, String providerId, String fieldKey) {}
    public record RankMapping(int rankOptionId, String providerId, String externalRankKey) {}
    public record DirectApiConfiguration(String providerId, boolean enabled) {}
    public record EffectiveApiConfiguration(String providerId, int sourceGameId, boolean inherited) {}

    private static void ensureAccountVerificationColumn(Connection conn) throws SQLException {
        try (ResultSet columns = conn.getMetaData().getColumns(
                conn.getCatalog(), null, "game_profile_api_account", "verification_method")) {
            if (columns.next()) return;
        }
        try (Statement statement = conn.createStatement()) {
            statement.executeUpdate("ALTER TABLE game_profile_api_account ADD COLUMN " +
                    "verification_method VARCHAR(24) NOT NULL DEFAULT 'RIOT_ID_FALLBACK' AFTER shard");
        }
    }

    private static GameApiProvider.AccountVerification verification(String value) {
        try {
            return GameApiProvider.AccountVerification.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return GameApiProvider.AccountVerification.RIOT_ID_FALLBACK;
        }
    }
}
