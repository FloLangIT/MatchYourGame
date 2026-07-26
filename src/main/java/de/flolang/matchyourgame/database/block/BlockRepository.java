package de.flolang.matchyourgame.database.block;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

public final class BlockRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(BlockRepository.class);

    private BlockRepository() {}

    public static void init() {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "CREATE TABLE IF NOT EXISTS user_block (" +
                        "blocker_user_id BIGINT NOT NULL," +
                        "blocked_user_id BIGINT NOT NULL," +
                        "block_count INT NOT NULL DEFAULT 1," +
                        "blocked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                        "expires_at TIMESTAMP NULL," +
                        "PRIMARY KEY (blocker_user_id,blocked_user_id)," +
                        "FOREIGN KEY (blocker_user_id) REFERENCES user(id)," +
                        "FOREIGN KEY (blocked_user_id) REFERENCES user(id))")) {
            ps.executeUpdate();
            LOGGER.info("User block table created if not exist");
        } catch (SQLException exception) {
            LOGGER.error("Could not initialize user blocks", exception);
        }
    }

    public static BlockResult block(int blockerId, int blockedId) {
        if (blockerId == blockedId) return BlockResult.FAILED;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int previousCount = 0;
                try (PreparedStatement select = conn.prepareStatement(
                        "SELECT block_count FROM user_block WHERE blocker_user_id=? AND blocked_user_id=? FOR UPDATE")) {
                    select.setInt(1, blockerId);
                    select.setInt(2, blockedId);
                    try (ResultSet rs = select.executeQuery()) {
                        if (rs.next()) previousCount = rs.getInt("block_count");
                    }
                }
                int newCount = previousCount + 1;
                Timestamp expiresAt = newCount >= 2 ? null
                        : Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS));
                try (PreparedStatement upsert = conn.prepareStatement(
                        "INSERT INTO user_block (blocker_user_id,blocked_user_id,block_count,blocked_at,expires_at) " +
                                "VALUES (?,?,?,CURRENT_TIMESTAMP,?) ON DUPLICATE KEY UPDATE " +
                                "block_count=VALUES(block_count),blocked_at=CURRENT_TIMESTAMP,expires_at=VALUES(expires_at)")) {
                    upsert.setInt(1, blockerId);
                    upsert.setInt(2, blockedId);
                    upsert.setInt(3, newCount);
                    upsert.setTimestamp(4, expiresAt);
                    upsert.executeUpdate();
                }
                conn.commit();
                return newCount >= 2 ? BlockResult.PERMANENT : BlockResult.TEMPORARY;
            } catch (SQLException exception) {
                conn.rollback();
                throw exception;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            LOGGER.error("Could not block user {} for user {}", blockedId, blockerId, exception);
            return BlockResult.FAILED;
        }
    }

    public static boolean isActive(int blockerId, int blockedId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM user_block WHERE blocker_user_id=? AND blocked_user_id=? " +
                        "AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)")) {
            ps.setInt(1, blockerId);
            ps.setInt(2, blockedId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException exception) {
            LOGGER.error("Could not check block between users {} and {}", blockerId, blockedId, exception);
            return false;
        }
    }

    public static boolean conflictsWithAny(int userId, List<Integer> otherUserIds) {
        for (int otherId : otherUserIds) {
            if (otherId != userId && (isActive(userId, otherId) || isActive(otherId, userId))) return true;
        }
        return false;
    }

    public static boolean hasLobbyConflict(int userId, int lobbyId) {
        return conflictsWithAny(userId,
                de.flolang.matchyourgame.database.lobby.LobbyRepository.memberIds(lobbyId));
    }

    public static void deleteAllForUser(int userId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM user_block WHERE blocker_user_id=? OR blocked_user_id=?")) {
            ps.setInt(1, userId);
            ps.setInt(2, userId);
            ps.executeUpdate();
        } catch (SQLException exception) {
            LOGGER.error("Could not remove blocks for user {}", userId, exception);
        }
    }

    public enum BlockResult {
        TEMPORARY,
        PERMANENT,
        FAILED
    }
}
