package de.flolang.matchyourgame.database.game;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

public final class RankCompatibilityRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(RankCompatibilityRepository.class);

    private RankCompatibilityRepository() {}

    public static void init() {
        try (Connection conn = Database.getConnection(); Statement statement = conn.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS game_rank_compatibility (" +
                    "game_id BIGINT NOT NULL," +
                    "source_rank_id BIGINT NOT NULL," +
                    "target_rank_id BIGINT NOT NULL," +
                    "PRIMARY KEY (game_id,source_rank_id,target_rank_id)," +
                    "FOREIGN KEY (game_id) REFERENCES game(id)," +
                    "FOREIGN KEY (source_rank_id) REFERENCES game_option(id)," +
                    "FOREIGN KEY (target_rank_id) REFERENCES game_option(id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS game_rank_settings (" +
                    "game_id BIGINT PRIMARY KEY," +
                    "unrestricted_party_size INT NULL," +
                    "FOREIGN KEY (game_id) REFERENCES game(id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS game_rank_rule_override (" +
                    "game_id BIGINT PRIMARY KEY," +
                    "FOREIGN KEY (game_id) REFERENCES game(id))");
        } catch (SQLException e) {
            LOGGER.error("Could not initialize rank compatibility", e);
        }
    }

    public static boolean isCompatible(int gameId, int lobbyRankOrder, int playerRankOrder) {
        if (lobbyRankOrder == playerRankOrder) return true;
        try {
            List<GameOption> ranks = GameOptionRepository.get(gameId, GameOption.Type.RANK);
            GameOption lobbyRank = byOrder(ranks, lobbyRankOrder);
            GameOption playerRank = byOrder(ranks, playerRankOrder);
            if (lobbyRank == null || playerRank == null) return false;
            int ruleGameId = ruleOwner(gameId);
            try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM game_rank_compatibility WHERE game_id=? AND source_rank_id=? AND target_rank_id=?")) {
                ps.setInt(1, ruleGameId); ps.setInt(2, lobbyRank.id()); ps.setInt(3, playerRank.id());
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            }
        } catch (SQLException | IllegalStateException e) {
            LOGGER.error("Could not check rank compatibility for game {}", gameId, e);
            return false;
        }
    }

    public static List<GameOption> getCompatibleRanks(int gameId, int sourceRankOrder) {
        List<GameOption> ranks = GameOptionRepository.get(gameId, GameOption.Type.RANK);
        GameOption source = byOrder(ranks, sourceRankOrder);
        if (source == null) return List.of();
        Set<Integer> compatibleIds = new HashSet<>();
        compatibleIds.add(source.id());
        try {
            int ruleGameId = ruleOwner(gameId);
            try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                    "SELECT target_rank_id FROM game_rank_compatibility WHERE game_id=? AND source_rank_id=?")) {
                ps.setInt(1, ruleGameId);
                ps.setInt(2, source.id());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) compatibleIds.add(rs.getInt("target_rank_id"));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Could not load compatible ranks for game {}", gameId, e);
        }
        return ranks.stream().filter(rank -> compatibleIds.contains(rank.id()))
                .sorted(Comparator.comparingInt(GameOption::sortOrder)).toList();
    }

    public static List<GameOption> getCompatibleRanksForSources(int gameId, List<Integer> sourceRankOrders) {
        List<GameOption> ranks = GameOptionRepository.get(gameId, GameOption.Type.RANK);
        if (sourceRankOrders.isEmpty()) return ranks;
        Set<Integer> allowedIds = ranks.stream().map(GameOption::id).collect(java.util.stream.Collectors.toSet());
        for (int sourceRankOrder : sourceRankOrders) {
            Set<Integer> sourceAllowed = getCompatibleRanks(gameId, sourceRankOrder).stream()
                    .map(GameOption::id).collect(java.util.stream.Collectors.toSet());
            allowedIds.retainAll(sourceAllowed);
        }
        return ranks.stream().filter(rank -> allowedIds.contains(rank.id()))
                .sorted(Comparator.comparingInt(GameOption::sortOrder)).toList();
    }

    /**
     * Groups are separated by semicolons and ranks inside a group by a pipe.
     * Every rank in one group is made compatible with every other rank in that group.
     * Example: Iron|Bronze|Silver;Silver|Gold;Gold|Platinum
     */
    public static void replaceGroups(int gameId, String specification) {
        List<GameOption> ranks = GameOptionRepository.get(gameId, GameOption.Type.RANK);
        Map<String, GameOption> byName = new HashMap<>();
        for (GameOption rank : ranks) byName.put(rank.name().toLowerCase(Locale.ROOT), rank);
        Set<RankPair> pairs = new LinkedHashSet<>();
        for (RankCompatibilityGroups.Pair parsed : RankCompatibilityGroups.parse(specification)) {
            GameOption source = byName.get(parsed.source());
            GameOption target = byName.get(parsed.target());
            if (source == null) throw new IllegalArgumentException("Unknown rank in compatibility group: " + parsed.source());
            if (target == null) throw new IllegalArgumentException("Unknown rank in compatibility group: " + parsed.target());
            pairs.add(new RankPair(source.id(), target.id()));
        }
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement delete = conn.prepareStatement("DELETE FROM game_rank_compatibility WHERE game_id=?");
                 PreparedStatement override = conn.prepareStatement("INSERT IGNORE INTO game_rank_rule_override (game_id) VALUES (?)");
                 PreparedStatement insert = conn.prepareStatement(
                         "INSERT INTO game_rank_compatibility (game_id,source_rank_id,target_rank_id) VALUES (?,?,?)")) {
                delete.setInt(1, gameId); delete.executeUpdate();
                override.setInt(1, gameId); override.executeUpdate();
                for (RankPair pair : pairs) {
                    insert.setInt(1, gameId); insert.setInt(2, pair.sourceRankId()); insert.setInt(3, pair.targetRankId()); insert.addBatch();
                }
                insert.executeBatch(); conn.commit();
            } catch (SQLException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) {
            throw new IllegalStateException("Rank compatibility could not be saved", e);
        }
    }

    /**
     * Returns an editable specification for the effective compatibility rules.
     * Two-rank groups are used so saving the generated text recreates the exact
     * relation without accidentally making overlapping groups transitive.
     */
    public static String getGroupSpecification(int gameId) {
        try {
            int owner = ruleOwner(gameId);
            String sql = "SELECT s.id source_id,s.name source_name,t.id target_id,t.name target_name " +
                    "FROM game_rank_compatibility c " +
                    "JOIN game_option s ON s.id=c.source_rank_id " +
                    "JOIN game_option t ON t.id=c.target_rank_id " +
                    "WHERE c.game_id=? ORDER BY s.sort_order,t.sort_order";
            Set<String> seen = new HashSet<>();
            List<String> groups = new ArrayList<>();
            try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, owner);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int sourceId = rs.getInt("source_id"), targetId = rs.getInt("target_id");
                        String key = Math.min(sourceId, targetId) + ":" + Math.max(sourceId, targetId);
                        if (seen.add(key))
                            groups.add(rs.getString("source_name") + "|" + rs.getString("target_name"));
                    }
                }
            }
            return groups.isEmpty() ? "-" : String.join(";", groups);
        } catch (SQLException e) {
            LOGGER.error("Could not serialize rank compatibility for game {}", gameId, e);
            return "-";
        }
    }

    public static boolean inheritsGroups(int gameId) {
        GameObject game = GameRepository.get(gameId);
        if (game == null || game.getSubGameFrom() == null) return false;
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM game_rank_rule_override WHERE game_id=?")) {
            ps.setInt(1, gameId);
            try (ResultSet rs = ps.executeQuery()) { return !rs.next(); }
        } catch (SQLException e) {
            LOGGER.error("Could not check rank-rule inheritance for game {}", gameId, e);
            return false;
        }
    }

    public static void clearGroupOverride(int gameId) {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement rules = conn.prepareStatement(
                    "DELETE FROM game_rank_compatibility WHERE game_id=?");
                 PreparedStatement override = conn.prepareStatement(
                         "DELETE FROM game_rank_rule_override WHERE game_id=?")) {
                rules.setInt(1, gameId); rules.executeUpdate();
                override.setInt(1, gameId); override.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback(); throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Rank compatibility inheritance could not be restored", e);
        }
    }

    public static void setUnrestrictedPartySize(int gameId, Integer partySize) {
        if (partySize != null && partySize < 2) throw new IllegalArgumentException("Unrestricted party size must be at least 2");
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO game_rank_settings (game_id,unrestricted_party_size) VALUES (?,?) " +
                        "ON DUPLICATE KEY UPDATE unrestricted_party_size=VALUES(unrestricted_party_size)")) {
            ps.setInt(1, gameId);
            if (partySize == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, partySize);
            ps.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Rank settings could not be saved", e); }
    }

    public static Integer getUnrestrictedPartySize(int gameId) {
        try {
            RankSetting direct = directUnrestrictedPartySize(gameId);
            if (direct.found()) return direct.partySize();
            GameObject game = GameRepository.get(gameId);
            return game != null && game.getSubGameFrom() != null
                    ? directUnrestrictedPartySize(game.getSubGameFrom().getId()).partySize() : null;
        } catch (SQLException | IllegalStateException e) {
            LOGGER.error("Could not load unrestricted party size for game {}", gameId, e);
            return null;
        }
    }

    public static boolean inheritsUnrestrictedPartySize(int gameId) {
        GameObject game = GameRepository.get(gameId);
        if (game == null || game.getSubGameFrom() == null) return false;
        try {
            return !directUnrestrictedPartySize(gameId).found();
        } catch (SQLException e) {
            LOGGER.error("Could not check unrestricted rank-rule inheritance for game {}", gameId, e);
            return false;
        }
    }

    public static void clearUnrestrictedPartySizeOverride(int gameId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM game_rank_settings WHERE game_id=?")) {
            ps.setInt(1, gameId); ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Unrestricted party-size inheritance could not be restored", e);
        }
    }

    private static RankSetting directUnrestrictedPartySize(int gameId) throws SQLException {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT unrestricted_party_size FROM game_rank_settings WHERE game_id=?")) {
            ps.setInt(1, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new RankSetting(true, (Integer) rs.getObject(1));
                return new RankSetting(false, null);
            }
        }
    }

    private static int ruleOwner(int gameId) throws SQLException {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM game_rank_rule_override WHERE game_id=?")) {
            ps.setInt(1, gameId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return gameId; }
        }
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM game_rank_compatibility WHERE game_id=? LIMIT 1")) {
            ps.setInt(1, gameId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return gameId; }
        }
        GameObject game = GameRepository.get(gameId);
        return game != null && game.getSubGameFrom() != null ? game.getSubGameFrom().getId() : gameId;
    }

    private static GameOption byOrder(List<GameOption> ranks, int order) {
        return ranks.stream().filter(rank -> rank.sortOrder() == order).findFirst().orElse(null);
    }

    private record RankPair(int sourceRankId, int targetRankId) {}
    private record RankSetting(boolean found, Integer partySize) {}
}
