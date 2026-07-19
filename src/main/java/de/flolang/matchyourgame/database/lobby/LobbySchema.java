package de.flolang.matchyourgame.database.lobby;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class LobbySchema {
    private static final Logger LOGGER = LoggerFactory.getLogger(LobbySchema.class);

    private LobbySchema() {}

    static void init() {
        try (Connection conn = Database.getConnection(); Statement statement = conn.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS lobby (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "game_id BIGINT NOT NULL," +
                    "leader_id BIGINT NOT NULL," +
                    "guild_id VARCHAR(32) NULL," +
                    "voicechannel_ID VARCHAR(32) NULL," +
                    "voice_invite_url VARCHAR(255) NULL," +
                    "max_players INT NOT NULL DEFAULT 2," +
                    "platform VARCHAR(40) NOT NULL DEFAULT 'ANY'," +
                    "region VARCHAR(40) NOT NULL DEFAULT 'ANY'," +
                    "language VARCHAR(10) NOT NULL DEFAULT 'ANY'," +
                    "rank_min INT NOT NULL DEFAULT 0," +
                    "rank_max INT NOT NULL DEFAULT 2147483647," +
                    "custom_rank_min INT NULL," +
                    "custom_rank_max INT NULL," +
                    "rank_rules_unrestricted BOOLEAN NOT NULL DEFAULT FALSE," +
                    "preferred_role VARCHAR(40) NOT NULL DEFAULT 'ANY'," +
                    "status VARCHAR(12) NOT NULL DEFAULT 'OPEN'," +
                    "passive_queue BOOLEAN NOT NULL DEFAULT FALSE," +
                    "clan_queue BOOLEAN NOT NULL DEFAULT FALSE," +
                    "last_invite_wave_at TIMESTAMP NULL," +
                    "voice_created_at TIMESTAMP NULL," +
                    "voice_reminder_sent_at TIMESTAMP NULL," +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "closed_at TIMESTAMP NULL DEFAULT NULL," +
                    "FOREIGN KEY (game_id) REFERENCES game(id)," +
                    "FOREIGN KEY (leader_id) REFERENCES user(id)," +
                    "FOREIGN KEY (guild_id) REFERENCES guild(guild_id))");

            migrateLegacyLobby(conn, statement);

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS search_profile (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "user_id BIGINT NOT NULL," +
                    "game_id BIGINT NOT NULL," +
                    "platform VARCHAR(40) NOT NULL DEFAULT 'ANY'," +
                    "region VARCHAR(40) NOT NULL DEFAULT 'ANY'," +
                    "language VARCHAR(10) NOT NULL DEFAULT 'ANY'," +
                    "rank_value INT NOT NULL DEFAULT 0," +
                    "preferred_role VARCHAR(40) NOT NULL DEFAULT 'ANY'," +
                    "passive_enabled BOOLEAN NOT NULL DEFAULT FALSE," +
                    "last_invited_at TIMESTAMP NULL," +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                    "UNIQUE KEY uq_search_profile (user_id,game_id,platform)," +
                    "FOREIGN KEY (user_id) REFERENCES user(id)," +
                    "FOREIGN KEY (game_id) REFERENCES game(id))");
            migrateSearchProfileKey(conn, statement);

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS party (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "host_user_id BIGINT NOT NULL," +
                    "status VARCHAR(12) NOT NULL DEFAULT 'OPEN'," +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "activity_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY (host_user_id) REFERENCES user(id))");
            addColumnIfMissing(conn, statement, "party", "activity_at",
                    "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS party_member (" +
                    "party_id BIGINT NOT NULL," +
                    "user_id BIGINT NOT NULL," +
                    "joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "PRIMARY KEY (party_id,user_id)," +
                    "UNIQUE KEY uq_active_party_user (user_id)," +
                    "FOREIGN KEY (party_id) REFERENCES party(id)," +
                    "FOREIGN KEY (user_id) REFERENCES user(id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS party_invitation (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "party_id BIGINT NOT NULL," +
                    "inviter_id BIGINT NOT NULL," +
                    "invitee_id BIGINT NOT NULL," +
                    "status VARCHAR(12) NOT NULL DEFAULT 'PENDING'," +
                    "sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "responded_at TIMESTAMP NULL," +
                    "UNIQUE KEY uq_party_invitation (party_id,invitee_id)," +
                    "FOREIGN KEY (party_id) REFERENCES party(id)," +
                    "FOREIGN KEY (inviter_id) REFERENCES user(id)," +
                    "FOREIGN KEY (invitee_id) REFERENCES user(id))");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS clan (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "name VARCHAR(60) NOT NULL UNIQUE," +
                    "owner_user_id BIGINT NOT NULL," +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY (owner_user_id) REFERENCES user(id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS clan_member (" +
                    "clan_id BIGINT NOT NULL," +
                    "user_id BIGINT NOT NULL," +
                    "role VARCHAR(12) NOT NULL DEFAULT 'MEMBER'," +
                    "joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "PRIMARY KEY (clan_id,user_id)," +
                    "FOREIGN KEY (clan_id) REFERENCES clan(id)," +
                    "FOREIGN KEY (user_id) REFERENCES user(id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS clan_game (" +
                    "clan_id BIGINT NOT NULL," +
                    "game_id BIGINT NOT NULL," +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "PRIMARY KEY (clan_id,game_id)," +
                    "FOREIGN KEY (clan_id) REFERENCES clan(id)," +
                    "FOREIGN KEY (game_id) REFERENCES game(id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS clan_invitation (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "clan_id BIGINT NOT NULL," +
                    "inviter_id BIGINT NOT NULL," +
                    "invitee_id BIGINT NOT NULL," +
                    "status VARCHAR(12) NOT NULL DEFAULT 'PENDING'," +
                    "sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "responded_at TIMESTAMP NULL," +
                    "FOREIGN KEY (clan_id) REFERENCES clan(id)," +
                    "FOREIGN KEY (inviter_id) REFERENCES user(id)," +
                    "FOREIGN KEY (invitee_id) REFERENCES user(id))");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS lobby_member (" +
                    "lobby_id BIGINT NOT NULL," +
                    "user_id BIGINT NOT NULL," +
                    "party_id BIGINT NULL," +
                    "joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "voice_joined_at TIMESTAMP NULL," +
                    "voice_rejoin_deadline TIMESTAMP NULL," +
                    "voice_extension_used BOOLEAN NOT NULL DEFAULT FALSE," +
                    "left_at TIMESTAMP NULL," +
                    "PRIMARY KEY (lobby_id,user_id)," +
                    "FOREIGN KEY (lobby_id) REFERENCES lobby(id)," +
                    "FOREIGN KEY (user_id) REFERENCES user(id)," +
                    "FOREIGN KEY (party_id) REFERENCES party(id))");
            migrateLegacyLobbyMember(conn, statement);
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS lobby_invitation (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "lobby_id BIGINT NOT NULL," +
                    "user_id BIGINT NOT NULL," +
                    "source VARCHAR(20) NOT NULL," +
                    "status VARCHAR(12) NOT NULL DEFAULT 'PENDING'," +
                    "discord_message_id VARCHAR(32) NULL," +
                    "sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "responded_at TIMESTAMP NULL," +
                    "UNIQUE KEY uq_lobby_invitation (lobby_id,user_id)," +
                    "FOREIGN KEY (lobby_id) REFERENCES lobby(id)," +
                    "FOREIGN KEY (user_id) REFERENCES user(id))");
            addColumnIfMissing(conn, statement, "lobby_invitation", "discord_message_id", "VARCHAR(32) NULL");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS lobby_communication_language (" +
                    "lobby_id BIGINT NOT NULL,language_code VARCHAR(20) NOT NULL," +
                    "PRIMARY KEY (lobby_id,language_code),FOREIGN KEY (lobby_id) REFERENCES lobby(id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS lobby_excluded_user (" +
                    "lobby_id BIGINT NOT NULL,user_id BIGINT NOT NULL,excluded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "PRIMARY KEY (lobby_id,user_id),FOREIGN KEY (lobby_id) REFERENCES lobby(id)," +
                    "FOREIGN KEY (user_id) REFERENCES user(id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS lobby_clan_queue (" +
                    "lobby_id BIGINT NOT NULL," +
                    "clan_id BIGINT NOT NULL," +
                    "added_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "PRIMARY KEY (lobby_id,clan_id)," +
                    "FOREIGN KEY (lobby_id) REFERENCES lobby(id)," +
                    "FOREIGN KEY (clan_id) REFERENCES clan(id))");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS lobby_match (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "lobby_id BIGINT NOT NULL," +
                    "sequence_number INT NOT NULL," +
                    "status VARCHAR(24) NOT NULL DEFAULT 'DRAFT'," +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "UNIQUE KEY uq_lobby_match_sequence (lobby_id,sequence_number)," +
                    "FOREIGN KEY (lobby_id) REFERENCES lobby(id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS match_team (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "match_id BIGINT NOT NULL," +
                    "name VARCHAR(40) NOT NULL," +
                    "FOREIGN KEY (match_id) REFERENCES lobby_match(id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS match_participant (" +
                    "match_id BIGINT NOT NULL," +
                    "user_id BIGINT NOT NULL," +
                    "team_id BIGINT NOT NULL," +
                    "PRIMARY KEY (match_id,user_id)," +
                    "FOREIGN KEY (match_id) REFERENCES lobby_match(id)," +
                    "FOREIGN KEY (user_id) REFERENCES user(id)," +
                    "FOREIGN KEY (team_id) REFERENCES match_team(id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS match_stat_value (" +
                    "match_id BIGINT NOT NULL," +
                    "stat_definition_id BIGINT NOT NULL," +
                    "user_id BIGINT NULL," +
                    "team_id BIGINT NULL," +
                    "value_text TEXT NOT NULL," +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "FOREIGN KEY (match_id) REFERENCES lobby_match(id)," +
                    "FOREIGN KEY (stat_definition_id) REFERENCES game_stat_definition(id)," +
                    "FOREIGN KEY (user_id) REFERENCES user(id)," +
                    "FOREIGN KEY (team_id) REFERENCES match_team(id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS match_confirmation (" +
                    "match_id BIGINT NOT NULL," +
                    "user_id BIGINT NOT NULL," +
                    "confirmed BOOLEAN NOT NULL," +
                    "comment VARCHAR(500) NULL," +
                    "responded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "PRIMARY KEY (match_id,user_id)," +
                    "FOREIGN KEY (match_id) REFERENCES lobby_match(id)," +
                    "FOREIGN KEY (user_id) REFERENCES user(id))");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS review_assignment (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "lobby_id BIGINT NOT NULL," +
                    "reviewer_user_id BIGINT NOT NULL," +
                    "target_user_id BIGINT NOT NULL," +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "completed_at TIMESTAMP NULL," +
                    "UNIQUE KEY uq_lobby_reviewer (lobby_id,reviewer_user_id)," +
                    "FOREIGN KEY (lobby_id) REFERENCES lobby(id)," +
                    "FOREIGN KEY (reviewer_user_id) REFERENCES user(id)," +
                    "FOREIGN KEY (target_user_id) REFERENCES user(id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS player_review (" +
                    "assignment_id BIGINT PRIMARY KEY," +
                    "behavior_stars TINYINT NOT NULL," +
                    "teamplay_stars TINYINT NOT NULL," +
                    "reliability_stars TINYINT NOT NULL," +
                    "private_feedback TEXT NULL," +
                    "moderation_stars TINYINT NULL," +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "moderated_at TIMESTAMP NULL," +
                    "FOREIGN KEY (assignment_id) REFERENCES review_assignment(id))");
            addIndexIfMissing(conn, statement, "review_assignment", "idx_review_pair",
                    "(reviewer_user_id,target_user_id)");
            deduplicatePlayerReviews(statement);
            LOGGER.info("Lobby feature schema initialized");
        } catch (SQLException e) {
            LOGGER.error("Could not initialize lobby feature schema", e);
        }
    }

    private static void migrateLegacyLobby(Connection conn, Statement statement) throws SQLException {
        addColumnIfMissing(conn, statement, "lobby", "max_players", "INT NOT NULL DEFAULT 2");
        addColumnIfMissing(conn, statement, "lobby", "platform", "VARCHAR(40) NOT NULL DEFAULT 'ANY'");
        addColumnIfMissing(conn, statement, "lobby", "region", "VARCHAR(40) NOT NULL DEFAULT 'ANY'");
        addColumnIfMissing(conn, statement, "lobby", "language", "VARCHAR(10) NOT NULL DEFAULT 'ANY'");
        addColumnIfMissing(conn, statement, "lobby", "rank_min", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing(conn, statement, "lobby", "rank_max", "INT NOT NULL DEFAULT 2147483647");
        addColumnIfMissing(conn, statement, "lobby", "custom_rank_min", "INT NULL");
        addColumnIfMissing(conn, statement, "lobby", "custom_rank_max", "INT NULL");
        addColumnIfMissing(conn, statement, "lobby", "rank_rules_unrestricted", "BOOLEAN NOT NULL DEFAULT FALSE");
        statement.executeUpdate("UPDATE lobby SET rank_rules_unrestricted=TRUE " +
                "WHERE rank_min=-1 AND rank_max=-1 AND rank_rules_unrestricted=FALSE");
        addColumnIfMissing(conn, statement, "lobby", "preferred_role", "VARCHAR(40) NOT NULL DEFAULT 'ANY'");
        addColumnIfMissing(conn, statement, "lobby", "status", "VARCHAR(12) NOT NULL DEFAULT 'OPEN'");
        addColumnIfMissing(conn, statement, "lobby", "passive_queue", "BOOLEAN NOT NULL DEFAULT FALSE");
        addColumnIfMissing(conn, statement, "lobby", "clan_queue", "BOOLEAN NOT NULL DEFAULT FALSE");
        addColumnIfMissing(conn, statement, "lobby", "last_invite_wave_at", "TIMESTAMP NULL");
        addColumnIfMissing(conn, statement, "lobby", "voice_created_at", "TIMESTAMP NULL");
        addColumnIfMissing(conn, statement, "lobby", "voice_invite_url", "VARCHAR(255) NULL");
        addColumnIfMissing(conn, statement, "lobby", "voice_reminder_sent_at", "TIMESTAMP NULL");
        try {
            statement.executeUpdate("ALTER TABLE lobby MODIFY closed_at TIMESTAMP NULL DEFAULT NULL");
        } catch (SQLException e) {
            LOGGER.warn("Could not make lobby.closed_at nullable; existing installations may require a manual migration", e);
        }
        try {
            statement.executeUpdate("ALTER TABLE lobby_match MODIFY status VARCHAR(24) NOT NULL DEFAULT 'DRAFT'");
        } catch (SQLException ignored) {
            // The table is created later on new installations.
        }
    }

    private static void migrateLegacyLobbyMember(Connection conn, Statement statement) throws SQLException {
        addColumnIfMissing(conn, statement, "lobby_member", "party_id", "BIGINT NULL");
        addColumnIfMissing(conn, statement, "lobby_member", "joined_at",
                "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        addColumnIfMissing(conn, statement, "lobby_member", "voice_joined_at", "TIMESTAMP NULL");
        addColumnIfMissing(conn, statement, "lobby_member", "voice_rejoin_deadline", "TIMESTAMP NULL");
        addColumnIfMissing(conn, statement, "lobby_member", "voice_extension_used", "BOOLEAN NOT NULL DEFAULT FALSE");
        addColumnIfMissing(conn, statement, "lobby_member", "left_at", "TIMESTAMP NULL");
    }

    private static void migrateSearchProfileKey(Connection conn, Statement statement) throws SQLException {
        java.util.Set<String> columns = new java.util.HashSet<>();
        try (ResultSet indexes = conn.getMetaData().getIndexInfo(conn.getCatalog(), null,
                "search_profile", true, false)) {
            while (indexes.next()) {
                if ("uq_search_profile".equalsIgnoreCase(indexes.getString("INDEX_NAME")))
                    columns.add(indexes.getString("COLUMN_NAME").toLowerCase());
            }
        }
        if (!columns.contains("platform"))
            statement.executeUpdate("ALTER TABLE search_profile DROP INDEX uq_search_profile," +
                    " ADD UNIQUE KEY uq_search_profile (user_id,game_id,platform)");
    }

    private static void addColumnIfMissing(Connection conn, Statement statement, String table,
                                           String column, String definition) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet columns = metaData.getColumns(conn.getCatalog(), null, table, column)) {
            if (!columns.next()) statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private static void addIndexIfMissing(Connection conn, Statement statement, String table,
                                          String indexName, String columns) throws SQLException {
        try (ResultSet indexes = conn.getMetaData().getIndexInfo(conn.getCatalog(), null, table, false, false)) {
            while (indexes.next()) {
                if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return;
            }
        }
        statement.executeUpdate("ALTER TABLE " + table + " ADD INDEX " + indexName + " " + columns);
    }

    private static void deduplicatePlayerReviews(Statement statement) throws SQLException {
        statement.executeUpdate("DELETE older FROM player_review older " +
                "JOIN review_assignment older_assignment ON older_assignment.id=older.assignment_id " +
                "JOIN player_review newer ON newer.assignment_id<>older.assignment_id " +
                "JOIN review_assignment newer_assignment ON newer_assignment.id=newer.assignment_id " +
                "WHERE older_assignment.reviewer_user_id=newer_assignment.reviewer_user_id " +
                "AND older_assignment.target_user_id=newer_assignment.target_user_id " +
                "AND (newer.created_at>older.created_at OR " +
                "(newer.created_at=older.created_at AND newer.assignment_id>older.assignment_id))");
    }
}
