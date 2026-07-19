package de.flolang.matchyourgame.database.friend;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class FriendRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(FriendRepository.class);

    public static void init() {
        try (Connection conn = Database.getConnection()) {
            conn.prepareStatement("CREATE TABLE IF NOT EXISTS friends(requester_id BIGINT NOT NULL," +
                    "receiver_id BIGINT NOT NULL," +
                    "sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "accepted_at TIMESTAMP NULL DEFAULT NULL," +
                    "PRIMARY KEY (requester_id, receiver_id)," +
                    "FOREIGN KEY (requester_id) REFERENCES user(id)," +
                    "FOREIGN KEY (receiver_id) REFERENCES user(id))").executeUpdate();
            LOGGER.info("Friend table created if not exist");
        } catch (SQLException e) {
            LOGGER.error("Error while creating friend table", e);
        }
    }

    public static FriendObject get(int user1, int user2) {
        try (Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM friends WHERE (requester_id = ? AND receiver_id = ?) OR (requester_id = ? AND receiver_id = ?)");
            preparedStatement.setInt(1, user1);
            preparedStatement.setInt(2, user2);
            preparedStatement.setInt(3, user2);
            preparedStatement.setInt(4, user1);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if(resultSet.next()) {
                    FriendObject friendObject = new FriendObject(resultSet.getInt("requester_id"), resultSet.getInt("receiver_id"), resultSet.getTimestamp("sent_at"), resultSet.getTimestamp("accepted_at"));
                    LOGGER.trace("Get friends {} and {}", user1, user2);
                    return friendObject;
                }
            }

        } catch (SQLException e) {
            LOGGER.error("Error while finding friends", e);
        }
        return null;
    }

    public static boolean areFriends(int user1, int user2) {
        FriendObject friendship = get(user1, user2);
        return friendship != null && friendship.getAccepted_at() != null;
    }

    public static boolean haveMutualFriend(int user1, int user2) {
        String sql = "SELECT 1 FROM (" +
                "SELECT CASE WHEN requester_id=? THEN receiver_id ELSE requester_id END friend_id " +
                "FROM friends WHERE (requester_id=? OR receiver_id=?) AND accepted_at IS NOT NULL" +
                ") first_friends JOIN (" +
                "SELECT CASE WHEN requester_id=? THEN receiver_id ELSE requester_id END friend_id " +
                "FROM friends WHERE (requester_id=? OR receiver_id=?) AND accepted_at IS NOT NULL" +
                ") second_friends USING (friend_id) LIMIT 1";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, user1); ps.setInt(2, user1); ps.setInt(3, user1);
            ps.setInt(4, user2); ps.setInt(5, user2); ps.setInt(6, user2);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            LOGGER.error("Error while checking mutual friends", e);
            return false;
        }
    }

    public static List<FriendObject> getFriends(int userID) {
        try (Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM friends WHERE requester_id = ? OR receiver_id = ?");
            preparedStatement.setInt(1, userID);
            preparedStatement.setInt(2, userID);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                List<FriendObject> friends = new ArrayList<>();
                while(resultSet.next()) {
                    friends.add(new FriendObject(resultSet.getInt("requester_id"), resultSet.getInt("receiver_id"), resultSet.getTimestamp("sent_at"), resultSet.getTimestamp("accepted_at")));
                }
                return friends;
            }
        } catch (SQLException e) {
            LOGGER.error("Error while finding friends", e);
        }
        return new ArrayList<>();
    }

    public static List<Integer> getAcceptedFriendIds(int userId) {
        List<Integer> ids = new ArrayList<>();
        String sql = "SELECT CASE WHEN requester_id=? THEN receiver_id ELSE requester_id END friend_id " +
                "FROM friends WHERE (requester_id=? OR receiver_id=?) AND accepted_at IS NOT NULL ORDER BY accepted_at,friend_id";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId); ps.setInt(2, userId); ps.setInt(3, userId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getInt("friend_id")); }
        } catch (SQLException e) { LOGGER.error("Error while listing accepted friends", e); }
        return ids;
    }

    public static List<Integer> getPartyOrLobbyActiveFriendIds(int userId) {
        List<Integer> ids = new ArrayList<>();
        String friends = "SELECT CASE WHEN requester_id=? THEN receiver_id ELSE requester_id END friend_id " +
                "FROM friends WHERE (requester_id=? OR receiver_id=?) AND accepted_at IS NOT NULL";
        String sql = "SELECT DISTINCT f.friend_id FROM (" + friends + ") f WHERE " +
                "EXISTS (SELECT 1 FROM party_member pm JOIN party p ON p.id=pm.party_id WHERE pm.user_id=f.friend_id AND p.status='OPEN') OR " +
                "EXISTS (SELECT 1 FROM lobby_member lm JOIN lobby l ON l.id=lm.lobby_id WHERE lm.user_id=f.friend_id " +
                "AND lm.left_at IS NULL AND l.status IN ('OPEN','FORMING','READY','ACTIVE'))";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId); ps.setInt(2, userId); ps.setInt(3, userId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getInt("friend_id")); }
        } catch (SQLException e) { LOGGER.error("Error while finding friends in parties or lobbies", e); }
        return ids;
    }

    public static boolean delete(int user1, int user2) {
        FriendObject friendship = get(user1, user2);
        if (friendship == null || friendship.getAccepted_at() == null) return false;
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM friends WHERE requester_id=? AND receiver_id=?")) {
            ps.setInt(1, friendship.getRequesterID()); ps.setInt(2, friendship.getReceiverID());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { LOGGER.error("Error while removing friendship", e); return false; }
    }

    public static void update(FriendObject friendObject) {
        try (Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("UPDATE friends SET accepted_at = ? WHERE requester_id = ? AND receiver_id = ?");
            preparedStatement.setTimestamp(1, friendObject.getAccepted_at());
            preparedStatement.setInt(2, friendObject.getRequesterID());
            preparedStatement.setInt(3, friendObject.getReceiverID());
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Error while updating friend", e);
        }
    }

    public static void create(int requesterID, int receiverID) {
        try (Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("INSERT INTO friends (requester_id, receiver_id, sent_at) VALUES (?, ?, CURRENT_TIMESTAMP)");
            preparedStatement.setInt(1, requesterID);
            preparedStatement.setInt(2, receiverID);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Error while creating friend", e);
        }
    }

    public static void delete(FriendObject friendObject) {
        try (Connection conn = Database.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("DELETE FROM friends WHERE requester_id = ? AND receiver_id = ?");
            preparedStatement.setInt(1, friendObject.getRequesterID());
            preparedStatement.setInt(2, friendObject.getReceiverID());
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Error while deleting friend", e);
        }
    }

    public static boolean deleteAllForUser(int userId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM friends WHERE requester_id=? OR receiver_id=?")) {
            ps.setInt(1, userId); ps.setInt(2, userId); ps.executeUpdate(); return true;
        } catch (SQLException e) { LOGGER.error("Error while removing all friendships for user {}", userId, e); return false; }
    }

}
