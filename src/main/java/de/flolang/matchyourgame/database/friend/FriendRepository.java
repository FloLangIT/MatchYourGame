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

}
