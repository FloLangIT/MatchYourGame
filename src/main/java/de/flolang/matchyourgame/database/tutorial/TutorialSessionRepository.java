package de.flolang.matchyourgame.database.tutorial;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

/**
 * Stores only tutorial progress. Simulated parties, lobbies and matches deliberately never touch
 * their production tables.
 */
public final class TutorialSessionRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(TutorialSessionRepository.class);

    private TutorialSessionRepository() {}

    public enum Step {
        WELCOME,
        GAME_SELECTION,
        PROFILE_SETUP,
        PARTY_INTRO,
        PARTY_CREATED,
        PARTY_INVITE_PENDING,
        PARTY_ACTIVE,
        LOBBY_SELECTION,
        LOBBY_ACTIVE,
        FRIEND_INVITES_PENDING,
        FRIEND_INVITED,
        CLAN_INVITES_PENDING,
        CLAN_INVITED,
        PASSIVE_INVITES_PENDING,
        LOBBY_FULL,
        MATCH_ENTRY,
        MATCH_PARTICIPANTS,
        MATCH_RECORDED,
        PASSIVE_EXAMPLE,
        FINISH,
        FINAL_LOBBY_MEMBER,
        SUMMARY
    }

    public record Session(int userId, Step step, List<Integer> modeIds, int profileIndex,
                          int lobbyGameId, boolean matchAdded, int partySize) {
        public Session withStep(Step next) {
            return new Session(userId, next, modeIds, profileIndex, lobbyGameId, matchAdded, partySize);
        }

        public Session withProfileIndex(int nextIndex) {
            return new Session(userId, step, modeIds, nextIndex, lobbyGameId, matchAdded, partySize);
        }

        public Session withLobbyGame(int gameId) {
            return new Session(userId, step, modeIds, profileIndex, gameId, matchAdded, partySize);
        }

        public Session withMatchAdded(boolean added) {
            return new Session(userId, step, modeIds, profileIndex, lobbyGameId, added, partySize);
        }

        public Session withPartySize(int size) {
            return new Session(userId, step, modeIds, profileIndex, lobbyGameId, matchAdded,
                    Math.max(0, Math.min(2, size)));
        }
    }

    public static void init() {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS tutorial_session (" +
                    "user_id BIGINT PRIMARY KEY," +
                    "step VARCHAR(32) NOT NULL," +
                    "mode_ids VARCHAR(500) NOT NULL DEFAULT ''," +
                    "profile_index INT NOT NULL DEFAULT 0," +
                    "lobby_game_id BIGINT NOT NULL DEFAULT 0," +
                    "match_added BOOLEAN NOT NULL DEFAULT FALSE," +
                    "party_size INT NOT NULL DEFAULT 0," +
                    "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                    "FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE)");
            addColumnIfMissing(connection, "party_size", "INT NOT NULL DEFAULT 0");
        } catch (SQLException exception) {
            LOGGER.error("Could not initialize tutorial sessions", exception);
        }
    }

    public static Session reset(int userId) {
        Session session = new Session(userId, Step.WELCOME, List.of(), 0, 0, false, 0);
        return save(session) ? session : null;
    }

    public static Session get(int userId) {
        try (Connection connection = Database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM tutorial_session WHERE user_id=?")) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new Session(userId, Step.valueOf(result.getString("step")),
                        decode(result.getString("mode_ids")), result.getInt("profile_index"),
                        result.getInt("lobby_game_id"), result.getBoolean("match_added"),
                        result.getInt("party_size"));
            }
        } catch (SQLException | IllegalArgumentException exception) {
            LOGGER.error("Could not load tutorial session for user {}", userId, exception);
            return null;
        }
    }

    public static boolean save(Session session) {
        String sql = "INSERT INTO tutorial_session " +
                "(user_id,step,mode_ids,profile_index,lobby_game_id,match_added,party_size) VALUES (?,?,?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE step=VALUES(step),mode_ids=VALUES(mode_ids)," +
                "profile_index=VALUES(profile_index),lobby_game_id=VALUES(lobby_game_id)," +
                "match_added=VALUES(match_added),party_size=VALUES(party_size)";
        try (Connection connection = Database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, session.userId());
            statement.setString(2, session.step().name());
            statement.setString(3, session.modeIds().stream().map(String::valueOf)
                    .reduce((first, next) -> first + "," + next).orElse(""));
            statement.setInt(4, session.profileIndex());
            statement.setInt(5, session.lobbyGameId());
            statement.setBoolean(6, session.matchAdded());
            statement.setInt(7, session.partySize());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            LOGGER.error("Could not save tutorial session for user {}", session.userId(), exception);
            return false;
        }
    }

    public static void delete(int userId) {
        try (Connection connection = Database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM tutorial_session WHERE user_id=?")) {
            statement.setInt(1, userId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            LOGGER.error("Could not delete tutorial session for user {}", userId, exception);
        }
    }

    private static List<Integer> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return List.of();
        return Arrays.stream(encoded.split(",")).map(String::trim).filter(value -> !value.isBlank())
                .map(Integer::parseInt).toList();
    }

    private static void addColumnIfMissing(Connection connection, String column, String definition)
            throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(
                connection.getCatalog(), null, "tutorial_session", column)) {
            if (!columns.next())
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("ALTER TABLE tutorial_session ADD COLUMN " + column + " " + definition);
                }
        }
    }
}
