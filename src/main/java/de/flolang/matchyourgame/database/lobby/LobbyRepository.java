package de.flolang.matchyourgame.database.lobby;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class LobbyRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(LobbyRepository.class);

    private LobbyRepository() {}

    public static void init() {
        LobbySchema.init();
    }

    public static LobbyObject create(int gameId, int leaderId, int maxPlayers, String platform,
                                     String region, String language, int rankMin, int rankMax,
                                     String preferredRole) {
        String sql = "INSERT INTO lobby (game_id,leader_id,max_players,platform,region,language," +
                "rank_min,rank_max,rank_rules_unrestricted,preferred_role,status,passive_queue,clan_queue,closed_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,'OPEN',FALSE,FALSE,NULL)";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, gameId);
            ps.setInt(2, leaderId);
            ps.setInt(3, maxPlayers);
            ps.setString(4, normalize(platform));
            ps.setString(5, normalize(region));
            ps.setString(6, normalize(language));
            ps.setInt(7, rankMin);
            ps.setInt(8, rankMax);
            ps.setBoolean(9, rankMin == -1 && rankMax == -1);
            ps.setString(10, normalize(preferredRole));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    int id = keys.getInt(1);
                    addMember(id, leaderId, null);
                    return get(id);
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Could not create lobby for leader {}", leaderId, e);
        }
        return null;
    }

    public static LobbyObject get(int id) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM lobby WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            LOGGER.error("Could not get lobby {}", id, e);
            return null;
        }
    }

    public static LobbyObject getByVoiceChannel(long voiceChannelId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM lobby WHERE voicechannel_ID=?")) {
            ps.setString(1, Long.toString(voiceChannelId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            LOGGER.error("Could not get lobby by voice channel {}", voiceChannelId, e);
            return null;
        }
    }

    public static LobbyObject getOpenByLeader(int leaderId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM lobby WHERE leader_id=? AND status IN ('OPEN','FORMING','READY','ACTIVE') ORDER BY created_at DESC LIMIT 1")) {
            ps.setInt(1, leaderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            LOGGER.error("Could not load open lobby for leader {}", leaderId, e);
            return null;
        }
    }

    public static LobbyObject getActiveForUser(int userId) {
        String sql = "SELECT l.* FROM lobby l JOIN lobby_member lm ON lm.lobby_id=l.id " +
                "WHERE lm.user_id=? AND lm.left_at IS NULL AND l.status IN ('OPEN','FORMING','READY','ACTIVE') " +
                "ORDER BY l.created_at DESC LIMIT 1";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        } catch (SQLException e) {
            LOGGER.error("Could not load active lobby for user {}", userId, e);
            return null;
        }
    }

    public static List<LobbyObject> getAllActive() {
        List<LobbyObject> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM lobby WHERE status IN ('OPEN','FORMING','READY','ACTIVE') ORDER BY created_at DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(map(rs));
        } catch (SQLException e) { LOGGER.error("Could not list active lobbies", e); }
        return result;
    }

    public static List<LobbyObject> getOpenForGame(int gameId) {
        List<LobbyObject> result = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT l.* FROM lobby l WHERE l.game_id=? AND l.status='OPEN' " +
                             "AND (SELECT COUNT(*) FROM lobby_member lm WHERE lm.lobby_id=l.id AND lm.left_at IS NULL) < l.max_players " +
                             "ORDER BY l.created_at")) {
            ps.setInt(1, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        } catch (SQLException e) {
            LOGGER.error("Could not browse lobbies for game {}", gameId, e);
        }
        return result;
    }

    public static List<LobbyObject> getDueForInvitationWave(Duration interval) {
        List<LobbyObject> result = new ArrayList<>();
        Timestamp due = Timestamp.from(Instant.now().minus(interval));
        String sql = "SELECT * FROM lobby WHERE status='OPEN' AND (passive_queue=TRUE OR clan_queue=TRUE) " +
                "AND (last_invite_wave_at IS NULL OR last_invite_wave_at<=?)";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, due);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        } catch (SQLException e) {
            LOGGER.error("Could not find lobbies due for invitation wave", e);
        }
        return result;
    }

    public static List<LobbyObject> getDueForVoiceCheck(Duration timeout) {
        List<LobbyObject> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM lobby WHERE status='FORMING' AND voice_created_at<=?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now().minus(timeout)));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(map(rs)); }
        } catch (SQLException e) { LOGGER.error("Could not load voice check lobbies", e); }
        return result;
    }

    public static boolean addMember(int lobbyId, int userId, Integer partyId) throws SQLException {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int capacity;
                int members;
                try (PreparedStatement lock = conn.prepareStatement("SELECT max_players FROM lobby WHERE id=? AND status='OPEN' FOR UPDATE")) {
                    lock.setInt(1, lobbyId);
                    try (ResultSet rs = lock.executeQuery()) {
                        if (!rs.next()) return false;
                        capacity = rs.getInt(1);
                    }
                }
                try (PreparedStatement count = conn.prepareStatement("SELECT COUNT(*) FROM lobby_member WHERE lobby_id=? AND left_at IS NULL")) {
                    count.setInt(1, lobbyId);
                    try (ResultSet rs = count.executeQuery()) { rs.next(); members = rs.getInt(1); }
                }
                if (members >= capacity) { conn.rollback(); return false; }
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO lobby_member (lobby_id,user_id,party_id) VALUES (?,?,?)")) {
                    insert.setInt(1, lobbyId);
                    insert.setInt(2, userId);
                    if (partyId == null) insert.setNull(3, Types.BIGINT); else insert.setInt(3, partyId);
                    insert.executeUpdate();
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public static boolean addMembersAtomically(int lobbyId, List<Integer> userIds, Integer partyId) throws SQLException {
        if (userIds == null || userIds.isEmpty()) return false;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int capacity;
                int members;
                try (PreparedStatement lock = conn.prepareStatement(
                        "SELECT max_players FROM lobby WHERE id=? AND status='OPEN' FOR UPDATE")) {
                    lock.setInt(1, lobbyId);
                    try (ResultSet rs = lock.executeQuery()) {
                        if (!rs.next()) { conn.rollback(); return false; }
                        capacity = rs.getInt(1);
                    }
                }
                try (PreparedStatement count = conn.prepareStatement(
                        "SELECT COUNT(*) FROM lobby_member WHERE lobby_id=? AND left_at IS NULL")) {
                    count.setInt(1, lobbyId);
                    try (ResultSet rs = count.executeQuery()) { rs.next(); members = rs.getInt(1); }
                }
                if (members + userIds.size() > capacity) { conn.rollback(); return false; }
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO lobby_member (lobby_id,user_id,party_id) VALUES (?,?,?)")) {
                    for (int userId : userIds) {
                        insert.setInt(1, lobbyId); insert.setInt(2, userId);
                        if (partyId == null) insert.setNull(3, Types.BIGINT); else insert.setInt(3, partyId);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback(); throw e;
            } finally { conn.setAutoCommit(true); }
        }
    }

    public static boolean mergeOpenLobbies(int sourceLobbyId, int targetLobbyId, int sourceLeaderId) {
        if (sourceLobbyId == targetLobbyId) return false;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                boolean sourceValid = false;
                boolean targetValid = false;
                int targetCapacity = 0;
                try (PreparedStatement lock = conn.prepareStatement(
                        "SELECT id,leader_id,max_players,status,passive_queue FROM lobby " +
                                "WHERE id IN (?,?) ORDER BY id FOR UPDATE")) {
                    lock.setInt(1, sourceLobbyId);
                    lock.setInt(2, targetLobbyId);
                    try (ResultSet rs = lock.executeQuery()) {
                        while (rs.next()) {
                            int id = rs.getInt("id");
                            if (id == sourceLobbyId)
                                sourceValid = rs.getInt("leader_id") == sourceLeaderId
                                        && rs.getString("status").equals("OPEN");
                            if (id == targetLobbyId) {
                                targetValid = rs.getString("status").equals("OPEN")
                                        && rs.getBoolean("passive_queue");
                                targetCapacity = rs.getInt("max_players");
                            }
                        }
                    }
                }
                if (!sourceValid || !targetValid) { conn.rollback(); return false; }
                int sourceMembers = countMembers(conn, sourceLobbyId);
                int targetMembers = countMembers(conn, targetLobbyId);
                if (sourceMembers == 0 || sourceMembers + targetMembers > targetCapacity) {
                    conn.rollback(); return false;
                }
                try (PreparedStatement move = conn.prepareStatement(
                        "INSERT INTO lobby_member(lobby_id,user_id,party_id,joined_at,voice_joined_at," +
                                "voice_rejoin_deadline,voice_extension_used,left_at) " +
                                "SELECT ?,user_id,party_id,CURRENT_TIMESTAMP,NULL,NULL,FALSE,NULL FROM lobby_member " +
                                "WHERE lobby_id=? AND left_at IS NULL ON DUPLICATE KEY UPDATE " +
                                "party_id=VALUES(party_id),joined_at=CURRENT_TIMESTAMP,voice_joined_at=NULL," +
                                "voice_rejoin_deadline=NULL,voice_extension_used=FALSE,left_at=NULL");
                     PreparedStatement leaveSource = conn.prepareStatement(
                             "UPDATE lobby_member SET left_at=CURRENT_TIMESTAMP WHERE lobby_id=? AND left_at IS NULL");
                     PreparedStatement cancelSource = conn.prepareStatement(
                             "UPDATE lobby SET status='CANCELLED',closed_at=CURRENT_TIMESTAMP," +
                                     "passive_queue=FALSE,clan_queue=FALSE WHERE id=? AND status='OPEN'");
                     PreparedStatement cancelInvites = conn.prepareStatement(
                             "UPDATE lobby_invitation SET status='CANCELLED',responded_at=CURRENT_TIMESTAMP " +
                                     "WHERE lobby_id=? AND status='PENDING'")) {
                    move.setInt(1, targetLobbyId); move.setInt(2, sourceLobbyId); move.executeUpdate();
                    leaveSource.setInt(1, sourceLobbyId); leaveSource.executeUpdate();
                    cancelSource.setInt(1, sourceLobbyId);
                    if (cancelSource.executeUpdate() != 1) { conn.rollback(); return false; }
                    cancelInvites.setInt(1, sourceLobbyId); cancelInvites.executeUpdate();
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOGGER.error("Could not merge lobby {} into {}", sourceLobbyId, targetLobbyId, e);
            return false;
        }
    }

    private static int countMembers(Connection conn, int lobbyId) throws SQLException {
        try (PreparedStatement count = conn.prepareStatement(
                "SELECT COUNT(*) FROM lobby_member WHERE lobby_id=? AND left_at IS NULL")) {
            count.setInt(1, lobbyId);
            try (ResultSet rs = count.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    public static int memberCount(int lobbyId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM lobby_member WHERE lobby_id=? AND left_at IS NULL")) {
            ps.setInt(1, lobbyId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        } catch (SQLException e) {
            LOGGER.error("Could not count lobby members", e);
            return 0;
        }
    }

    public static List<Integer> memberIds(int lobbyId) {
        List<Integer> ids = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT user_id FROM lobby_member WHERE lobby_id=? AND left_at IS NULL ORDER BY joined_at")) {
            ps.setInt(1, lobbyId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getInt(1)); }
        } catch (SQLException e) {
            LOGGER.error("Could not list lobby members", e);
        }
        return ids;
    }

    public static List<Integer> allMemberIds(int lobbyId) {
        List<Integer> ids = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT user_id FROM lobby_member WHERE lobby_id=? ORDER BY joined_at,user_id")) {
            ps.setInt(1, lobbyId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getInt(1)); }
        } catch (SQLException e) {
            LOGGER.error("Could not list historical lobby members", e);
        }
        return ids;
    }

    public static void configureQueues(int lobbyId, boolean passive, boolean clan) {
        update("UPDATE lobby SET passive_queue=?,clan_queue=? WHERE id=?", ps -> {
            ps.setBoolean(1, passive); ps.setBoolean(2, clan); ps.setInt(3, lobbyId);
        });
    }

    public static void setPassiveQueue(int lobbyId, boolean enabled) {
        update("UPDATE lobby SET passive_queue=? WHERE id=?", ps -> {
            ps.setBoolean(1, enabled); ps.setInt(2, lobbyId);
        });
    }

    public static void setClanQueue(int lobbyId, boolean enabled) {
        update("UPDATE lobby SET clan_queue=? WHERE id=?", ps -> {
            ps.setBoolean(1, enabled); ps.setInt(2, lobbyId);
        });
    }

    public static void setCapacity(int lobbyId, int capacity) {
        update("UPDATE lobby SET max_players=? WHERE id=? AND status='OPEN'", ps -> {
            ps.setInt(1, capacity); ps.setInt(2, lobbyId);
        });
    }

    public static boolean updateSettings(int lobbyId, int capacity, Integer customRankMin,
                                         Integer customRankMax, boolean unrestrictedRanks) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE lobby SET max_players=?,custom_rank_min=?,custom_rank_max=?,rank_rules_unrestricted=?," +
                        "rank_min=CASE WHEN ?=FALSE AND rank_min=-1 THEN 0 ELSE rank_min END," +
                        "rank_max=CASE WHEN ?=FALSE AND rank_max=-1 THEN 2147483647 ELSE rank_max END " +
                        "WHERE id=? AND status IN ('OPEN','FORMING','READY','ACTIVE')")) {
            ps.setInt(1, capacity);
            if (customRankMin == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, customRankMin);
            if (customRankMax == null) ps.setNull(3, Types.INTEGER); else ps.setInt(3, customRankMax);
            ps.setBoolean(4, unrestrictedRanks);
            ps.setBoolean(5, unrestrictedRanks);
            ps.setBoolean(6, unrestrictedRanks);
            ps.setInt(7, lobbyId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOGGER.error("Could not update settings for lobby {}", lobbyId, e);
            return false;
        }
    }

    public static boolean addClanQueue(int lobbyId, int clanId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT IGNORE INTO lobby_clan_queue (lobby_id,clan_id) VALUES (?,?)")) {
            ps.setInt(1, lobbyId); ps.setInt(2, clanId); ps.executeUpdate();
            update("UPDATE lobby SET clan_queue=TRUE WHERE id=?", update -> update.setInt(1, lobbyId));
            return true;
        } catch (SQLException e) {
            LOGGER.error("Could not attach clan queue", e); return false;
        }
    }

    public static void markInvitationWave(int lobbyId) {
        update("UPDATE lobby SET last_invite_wave_at=CURRENT_TIMESTAMP WHERE id=?", ps -> ps.setInt(1, lobbyId));
    }

    public static void setVoiceChannel(int lobbyId, long guildId, long voiceChannelId) {
        update("UPDATE lobby SET guild_id=?,voicechannel_ID=?,voice_invite_url=NULL," +
                "voice_created_at=CURRENT_TIMESTAMP,status='FORMING' WHERE id=?", ps -> {
            ps.setString(1, Long.toString(guildId)); ps.setString(2, Long.toString(voiceChannelId)); ps.setInt(3, lobbyId);
        });
        update("UPDATE lobby_invitation li SET li.status='CANCELLED',li.responded_at=CURRENT_TIMESTAMP " +
                        "WHERE li.lobby_id=? AND li.status='PENDING' AND NOT EXISTS " +
                        "(SELECT 1 FROM lobby_member lm WHERE lm.lobby_id=li.lobby_id " +
                        "AND lm.user_id=li.user_id AND lm.left_at IS NULL)",
                ps -> ps.setInt(1, lobbyId));
        update("UPDATE lobby_member SET voice_joined_at=NULL,voice_rejoin_deadline=NULL,voice_extension_used=FALSE " +
                "WHERE lobby_id=? AND left_at IS NULL", ps -> ps.setInt(1, lobbyId));
    }

    public static void setVoiceInviteUrl(int lobbyId, String inviteUrl) {
        update("UPDATE lobby SET voice_invite_url=?,voice_created_at=CURRENT_TIMESTAMP," +
                "voice_reminder_sent_at=NULL WHERE id=?", ps -> {
            ps.setString(1, inviteUrl); ps.setInt(2, lobbyId);
        });
    }

    public static void markVoiceJoined(int lobbyId, int userId) {
        update("UPDATE lobby_member SET voice_joined_at=CURRENT_TIMESTAMP,voice_rejoin_deadline=NULL,voice_extension_used=FALSE " +
                        "WHERE lobby_id=? AND user_id=? AND left_at IS NULL",
                ps -> { ps.setInt(1, lobbyId); ps.setInt(2, userId); });
    }

    public static void storeVoiceReadyMessage(int lobbyId, int userId, String messageId) {
        update("INSERT INTO lobby_voice_ready_message (lobby_id,user_id,message_id) VALUES (?,?,?) " +
                "ON DUPLICATE KEY UPDATE message_id=VALUES(message_id)", ps -> {
            ps.setInt(1, lobbyId); ps.setInt(2, userId); ps.setString(3, messageId);
        });
    }

    public static String takeVoiceReadyMessage(int lobbyId, int userId) {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT message_id FROM lobby_voice_ready_message WHERE lobby_id=? AND user_id=? FOR UPDATE");
                 PreparedStatement delete = conn.prepareStatement(
                         "DELETE FROM lobby_voice_ready_message WHERE lobby_id=? AND user_id=?")) {
                select.setInt(1, lobbyId); select.setInt(2, userId);
                String messageId;
                try (ResultSet rs = select.executeQuery()) { messageId = rs.next() ? rs.getString(1) : null; }
                delete.setInt(1, lobbyId); delete.setInt(2, userId); delete.executeUpdate();
                conn.commit();
                return messageId;
            } catch (SQLException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) {
            LOGGER.error("Could not take voice-ready message for lobby {} user {}", lobbyId, userId, e);
            return null;
        }
    }

    public static void markVoiceLeft(int lobbyId, int userId) {
        update("UPDATE lobby_member SET voice_joined_at=NULL," +
                        "voice_rejoin_deadline=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 2 MINUTE)," +
                        "voice_extension_used=FALSE WHERE lobby_id=? AND user_id=? AND left_at IS NULL",
                ps -> { ps.setInt(1, lobbyId); ps.setInt(2, userId); });
    }

    public static boolean extendVoiceDeadline(int lobbyId, int userId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE lobby_member SET voice_rejoin_deadline=DATE_ADD(voice_rejoin_deadline,INTERVAL 2 MINUTE)," +
                        "voice_extension_used=TRUE WHERE lobby_id=? AND user_id=? AND left_at IS NULL " +
                        "AND voice_rejoin_deadline IS NOT NULL AND voice_extension_used=FALSE")) {
            ps.setInt(1, lobbyId); ps.setInt(2, userId); return ps.executeUpdate() == 1;
        } catch (SQLException e) { LOGGER.error("Could not extend voice deadline", e); return false; }
    }

    public static List<Integer> dueVoiceRejoinTimeouts(int lobbyId) {
        List<Integer> ids = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT user_id FROM lobby_member WHERE lobby_id=? AND left_at IS NULL " +
                        "AND voice_rejoin_deadline IS NOT NULL AND voice_rejoin_deadline<=CURRENT_TIMESTAMP")) {
            ps.setInt(1, lobbyId); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getInt(1)); }
        } catch (SQLException e) { LOGGER.error("Could not load voice rejoin timeouts", e); }
        return ids;
    }

    public static List<LobbyObject> getActiveWithVoice() {
        List<LobbyObject> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM lobby WHERE status IN ('FORMING','READY','ACTIVE') AND voicechannel_ID IS NOT NULL")) {
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(map(rs)); }
        } catch (SQLException e) { LOGGER.error("Could not load active voice lobbies", e); }
        return result;
    }

    public static List<LobbyObject> getDueForVoiceReminder() {
        List<LobbyObject> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM lobby WHERE status='FORMING' AND voice_created_at<=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 5 MINUTE) " +
                        "AND voice_reminder_sent_at IS NULL")) {
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(map(rs)); }
        } catch (SQLException e) { LOGGER.error("Could not load due voice reminders", e); }
        return result;
    }

    public static void markVoiceReminderSent(int lobbyId) {
        update("UPDATE lobby SET voice_reminder_sent_at=CURRENT_TIMESTAMP WHERE id=?", ps -> ps.setInt(1, lobbyId));
    }

    public static List<Integer> missingVoiceMembers(int lobbyId) {
        List<Integer> ids = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT user_id FROM lobby_member WHERE lobby_id=? AND left_at IS NULL AND voice_joined_at IS NULL")) {
            ps.setInt(1, lobbyId); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getInt(1)); }
        } catch (SQLException e) { LOGGER.error("Could not load missing voice members", e); }
        return ids;
    }

    public static List<Integer> missingInitialVoiceMembers(int lobbyId) {
        List<Integer> ids = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT user_id FROM lobby_member WHERE lobby_id=? AND left_at IS NULL " +
                        "AND voice_joined_at IS NULL AND voice_rejoin_deadline IS NULL")) {
            ps.setInt(1, lobbyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            LOGGER.error("Could not load members missing their initial voice join", e);
        }
        return ids;
    }

    public static void removeMembers(int lobbyId, List<Integer> userIds) {
        if (userIds.isEmpty()) return;
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE lobby_member SET left_at=CURRENT_TIMESTAMP WHERE lobby_id=? AND user_id=? AND left_at IS NULL")) {
            for (int userId : userIds) { ps.setInt(1, lobbyId); ps.setInt(2, userId); ps.addBatch(); }
            ps.executeBatch();
        } catch (SQLException e) { LOGGER.error("Could not remove missing voice members", e); }
    }

    public static void excludeUsers(int lobbyId, List<Integer> userIds) {
        if (userIds.isEmpty()) return;
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO lobby_excluded_user (lobby_id,user_id) VALUES (?,?)")) {
            for (int userId : userIds) { ps.setInt(1, lobbyId); ps.setInt(2, userId); ps.addBatch(); }
            ps.executeBatch();
        } catch (SQLException e) { LOGGER.error("Could not exclude kicked lobby users", e); }
    }

    public static Integer earliestActiveMember(int lobbyId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT user_id FROM lobby_member WHERE lobby_id=? AND left_at IS NULL ORDER BY joined_at,user_id LIMIT 1")) {
            ps.setInt(1, lobbyId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : null; }
        } catch (SQLException e) { LOGGER.error("Could not find next lobby host", e); return null; }
    }

    public static void promoteHost(int lobbyId, int userId) {
        update("UPDATE lobby SET leader_id=? WHERE id=?", ps -> { ps.setInt(1, userId); ps.setInt(2, lobbyId); });
    }

    public static void reopenAfterVoiceTimeout(int lobbyId) {
        update("UPDATE lobby SET status='OPEN',voicechannel_ID=NULL,voice_invite_url=NULL," +
                        "voice_created_at=NULL,voice_reminder_sent_at=NULL," +
                        "passive_queue=FALSE,clan_queue=FALSE,last_invite_wave_at=NULL WHERE id=?",
                ps -> ps.setInt(1, lobbyId));
        update("UPDATE lobby_member SET voice_joined_at=NULL,voice_rejoin_deadline=NULL,voice_extension_used=FALSE " +
                "WHERE lobby_id=? AND left_at IS NULL", ps -> ps.setInt(1, lobbyId));
    }

    public static void reopenPreservingVoiceChannel(int lobbyId) {
        update("UPDATE lobby SET status='OPEN',voice_invite_url=NULL,voice_reminder_sent_at=NULL," +
                        "passive_queue=FALSE,clan_queue=FALSE,last_invite_wave_at=NULL WHERE id=?",
                ps -> ps.setInt(1, lobbyId));
    }

    public static void beginExistingVoiceFormation(int lobbyId) {
        update("UPDATE lobby SET status='FORMING',voice_created_at=CURRENT_TIMESTAMP," +
                        "voice_invite_url=NULL,voice_reminder_sent_at=NULL WHERE id=?",
                ps -> ps.setInt(1, lobbyId));
        update("UPDATE lobby_invitation SET status='CANCELLED',responded_at=CURRENT_TIMESTAMP " +
                        "WHERE lobby_id=? AND status='PENDING'",
                ps -> ps.setInt(1, lobbyId));
    }

    public static void markActive(int lobbyId) {
        update("UPDATE lobby SET status='READY' WHERE id=?", ps -> ps.setInt(1, lobbyId));
    }

    public static void close(int lobbyId) {
        update("UPDATE lobby SET status='CLOSED',closed_at=CURRENT_TIMESTAMP WHERE id=?", ps -> ps.setInt(1, lobbyId));
        update("UPDATE lobby_invitation SET status='CANCELLED',responded_at=CURRENT_TIMESTAMP WHERE lobby_id=? AND status='PENDING'",
                ps -> ps.setInt(1, lobbyId));
    }

    public static void cancel(int lobbyId) {
        update("UPDATE lobby SET status='CANCELLED',closed_at=CURRENT_TIMESTAMP,passive_queue=FALSE,clan_queue=FALSE " +
                "WHERE id=? AND status='OPEN'", ps -> ps.setInt(1, lobbyId));
        update("UPDATE lobby_invitation SET status='CANCELLED',responded_at=CURRENT_TIMESTAMP " +
                "WHERE lobby_id=? AND status='PENDING'", ps -> ps.setInt(1, lobbyId));
    }

    private static LobbyObject map(ResultSet rs) throws SQLException {
        return new LobbyObject(rs.getInt("id"), rs.getInt("game_id"), rs.getInt("leader_id"),
                rs.getLong("guild_id"), rs.getLong("voicechannel_ID"), rs.getString("voice_invite_url"),
                rs.getInt("max_players"),
                rs.getString("platform"), rs.getString("region"), rs.getString("language"),
                rs.getInt("rank_min"), rs.getInt("rank_max"),
                (Integer) rs.getObject("custom_rank_min"), (Integer) rs.getObject("custom_rank_max"),
                rs.getBoolean("rank_rules_unrestricted"),
                rs.getString("preferred_role"),
                LobbyStatus.valueOf(rs.getString("status")), rs.getBoolean("passive_queue"),
                rs.getBoolean("clan_queue"), rs.getTimestamp("last_invite_wave_at"), rs.getTimestamp("voice_created_at"),
                rs.getTimestamp("created_at"), rs.getTimestamp("closed_at"));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "ANY" : value.trim().toUpperCase();
    }

    private interface StatementBinder { void bind(PreparedStatement ps) throws SQLException; }

    private static void update(String sql, StatementBinder binder) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps); ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Could not update lobby state", e);
        }
    }

    public static Timestamp getLastLobbyActivity(int userId) {
        String sql = "SELECT MAX(COALESCE(lm.left_at,l.closed_at,lm.joined_at)) last_activity " +
                "FROM lobby_member lm JOIN lobby l ON l.id=lm.lobby_id WHERE lm.user_id=?";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getTimestamp("last_activity") : null;
            }
        } catch (SQLException e) {
            LOGGER.error("Could not load last lobby activity for user {}", userId, e);
            return null;
        }
    }
}
