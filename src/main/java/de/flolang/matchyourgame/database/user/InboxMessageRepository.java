package de.flolang.matchyourgame.database.user;

import de.flolang.matchyourgame.database.Database;
import de.flolang.matchyourgame.language.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class InboxMessageRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(InboxMessageRepository.class);

    public enum DeliveryMode { SILENT, DIRECT_DM }
    public record InboxMessage(long id, int recipientUserId, int senderUserId, String title, String content,
                               boolean broadcast, DeliveryMode deliveryMode, String referenceType, Long referenceId,
                               Timestamp createdAt,
                               Timestamp deliveredAt, Timestamp readAt) {}

    private InboxMessageRepository() {}

    public static void init() {
        try (Connection conn = Database.getConnection(); Statement statement = conn.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS user_inbox_message (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY,recipient_user_id BIGINT NOT NULL," +
                    "sender_user_id BIGINT NOT NULL,title VARCHAR(100) NOT NULL,content TEXT NOT NULL," +
                    "broadcast BOOLEAN NOT NULL DEFAULT FALSE,delivery_mode VARCHAR(16) NOT NULL DEFAULT 'SILENT'," +
                    "batch_key VARCHAR(36) NULL,reference_type VARCHAR(32) NULL,reference_id BIGINT NULL," +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "delivered_at TIMESTAMP NULL,read_at TIMESTAMP NULL," +
                    "INDEX idx_inbox_recipient (recipient_user_id,read_at,created_at)," +
                    "FOREIGN KEY(recipient_user_id) REFERENCES user(id)," +
                    "FOREIGN KEY(sender_user_id) REFERENCES user(id))");
            addColumnIfMissing(conn, statement, "reference_type", "VARCHAR(32) NULL");
            addColumnIfMissing(conn, statement, "reference_id", "BIGINT NULL");
        } catch (SQLException e) { LOGGER.error("Could not initialize user inbox", e); }
    }

    public static InboxMessage create(int recipientId, int senderId, String title, String content,
                                      DeliveryMode deliveryMode) {
        return create(recipientId, senderId, title, content, deliveryMode, null, null);
    }

    public static InboxMessage create(int recipientId, int senderId, String title, String content,
                                      DeliveryMode deliveryMode, String referenceType, Long referenceId) {
        String sql = "INSERT INTO user_inbox_message(recipient_user_id,sender_user_id,title,content,delivery_mode," +
                "reference_type,reference_id) VALUES(?,?,?,?,?,?,?)";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, recipientId); ps.setInt(2, senderId); ps.setString(3, title);
            ps.setString(4, content); ps.setString(5, deliveryMode.name()); ps.setString(6, referenceType);
            if (referenceId == null) ps.setNull(7, Types.BIGINT); else ps.setLong(7, referenceId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) { return keys.next() ? get(keys.getLong(1), recipientId) : null; }
        } catch (SQLException e) { LOGGER.error("Could not create inbox message for user {}", recipientId, e); return null; }
    }

    public static List<InboxMessage> createBroadcast(int senderId, String title, String content,
                                                      DeliveryMode deliveryMode, Language language) {
        String batch = UUID.randomUUID().toString();
        String sql = "INSERT INTO user_inbox_message(recipient_user_id,sender_user_id,title,content,broadcast," +
                "delivery_mode,batch_key) SELECT id,?,?,?,?,?,? FROM user " +
                "WHERE anonymized=FALSE AND language=?";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, senderId); ps.setString(2, title); ps.setString(3, content);
            ps.setBoolean(4, true); ps.setString(5, deliveryMode.name()); ps.setString(6, batch);
            ps.setString(7, language.name());
            ps.executeUpdate();
            return getBatch(batch);
        } catch (SQLException e) { LOGGER.error("Could not create broadcast inbox messages", e); return List.of(); }
    }

    public static InboxMessage get(long id, int recipientId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM user_inbox_message WHERE id=? AND recipient_user_id=?")) {
            ps.setLong(1, id); ps.setInt(2, recipientId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        } catch (SQLException e) { LOGGER.error("Could not load inbox message {}", id, e); return null; }
    }

    public static List<InboxMessage> getForUser(int userId, int offset, int limit) {
        List<InboxMessage> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM user_inbox_message WHERE recipient_user_id=? " +
                        "ORDER BY read_at IS NULL DESC,created_at DESC,id DESC LIMIT ? OFFSET ?")) {
            ps.setInt(1, userId); ps.setInt(2, limit); ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(map(rs)); }
        } catch (SQLException e) { LOGGER.error("Could not list inbox messages for user {}", userId, e); }
        return result;
    }

    public static int count(int userId, boolean unreadOnly) {
        String sql = "SELECT COUNT(*) FROM user_inbox_message WHERE recipient_user_id=?" +
                (unreadOnly ? " AND read_at IS NULL" : "");
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) { LOGGER.error("Could not count inbox messages for user {}", userId, e); return 0; }
    }

    public static boolean markRead(long id, int recipientId) {
        return update("UPDATE user_inbox_message SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP) " +
                "WHERE id=? AND recipient_user_id=?", id, recipientId);
    }

    public static boolean markReadByReference(int recipientId, String referenceType, long referenceId) {
        String sql = "UPDATE user_inbox_message SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP) " +
                "WHERE recipient_user_id=? AND reference_type=? AND reference_id=?";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, recipientId); ps.setString(2, referenceType); ps.setLong(3, referenceId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.error("Could not mark referenced inbox message as read for user {}", recipientId, e);
            return false;
        }
    }

    public static boolean markDelivered(long id, int recipientId) {
        return update("UPDATE user_inbox_message SET delivered_at=CURRENT_TIMESTAMP WHERE id=? AND recipient_user_id=?",
                id, recipientId);
    }

    public static boolean delete(long id, int recipientId) {
        return update("DELETE FROM user_inbox_message WHERE id=? AND recipient_user_id=?", id, recipientId);
    }

    private static List<InboxMessage> getBatch(String batch) {
        List<InboxMessage> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM user_inbox_message WHERE batch_key=? ORDER BY id")) {
            ps.setString(1, batch);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(map(rs)); }
        } catch (SQLException e) { LOGGER.error("Could not load inbox broadcast batch", e); }
        return result;
    }

    private static boolean update(String sql, long id, int recipientId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id); ps.setInt(2, recipientId); return ps.executeUpdate() == 1;
        } catch (SQLException e) { LOGGER.error("Could not update inbox message {}", id, e); return false; }
    }

    private static InboxMessage map(ResultSet rs) throws SQLException {
        return new InboxMessage(rs.getLong("id"), rs.getInt("recipient_user_id"), rs.getInt("sender_user_id"),
                rs.getString("title"), rs.getString("content"), rs.getBoolean("broadcast"),
                DeliveryMode.valueOf(rs.getString("delivery_mode")), rs.getString("reference_type"),
                (Long) rs.getObject("reference_id"), rs.getTimestamp("created_at"),
                rs.getTimestamp("delivered_at"), rs.getTimestamp("read_at"));
    }

    private static void addColumnIfMissing(Connection conn, Statement statement, String column, String definition)
            throws SQLException {
        try (ResultSet columns = conn.getMetaData().getColumns(conn.getCatalog(), null,
                "user_inbox_message", column)) {
            if (!columns.next()) statement.executeUpdate(
                    "ALTER TABLE user_inbox_message ADD COLUMN " + column + " " + definition);
        }
    }
}
