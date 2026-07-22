package de.flolang.matchyourgame.database.match;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MatchRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(MatchRepository.class);

    private MatchRepository() {}

    public static int create(int lobbyId) {
        String sql = "INSERT INTO lobby_match (lobby_id,sequence_number) SELECT ?,COALESCE(MAX(sequence_number),0)+1 FROM lobby_match WHERE lobby_id=?";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, lobbyId); ps.setInt(2, lobbyId); ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) { return keys.next() ? keys.getInt(1) : 0; }
        } catch (SQLException e) { LOGGER.error("Could not create match", e); return 0; }
    }

    public static int addTeam(int matchId, String name) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO match_team (match_id,name) VALUES (?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, matchId); ps.setString(2, name); ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) { return keys.next() ? keys.getInt(1) : 0; }
        } catch (SQLException e) { LOGGER.error("Could not add match team", e); return 0; }
    }

    public static boolean addParticipant(int matchId, int userId, int teamId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO match_participant (match_id,user_id,team_id) VALUES (?,?,?)")) {
            ps.setInt(1, matchId); ps.setInt(2, userId); ps.setInt(3, teamId); return ps.executeUpdate() == 1;
        } catch (SQLException e) { LOGGER.error("Could not add match participant", e); return false; }
    }

    public static boolean addParticipants(int matchId, int teamId, List<Integer> userIds) {
        if (userIds.isEmpty()) return false;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO match_participant (match_id,user_id,team_id) VALUES (?,?,?)")) {
                for (int userId : userIds) {
                    ps.setInt(1, matchId); ps.setInt(2, userId); ps.setInt(3, teamId); ps.addBatch();
                }
                ps.executeBatch(); conn.commit(); return true;
            } catch (SQLException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) {
            LOGGER.error("Could not add participants to match {}", matchId, e);
            return false;
        }
    }

    public static boolean saveStat(int matchId, int definitionId, Integer userId, Integer teamId, String value) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO match_stat_value (match_id,stat_definition_id,user_id,team_id,value_text) VALUES (?,?,?,?,?)")) {
            ps.setInt(1, matchId); ps.setInt(2, definitionId);
            if (userId == null) ps.setNull(3, Types.BIGINT); else ps.setInt(3, userId);
            if (teamId == null) ps.setNull(4, Types.BIGINT); else ps.setInt(4, teamId);
            ps.setString(5, value); return ps.executeUpdate() == 1;
        } catch (SQLException e) { LOGGER.error("Could not save match statistic", e); return false; }
    }

    public static List<MatchInfo> getForLobby(int lobbyId) {
        List<MatchInfo> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT id,lobby_id,sequence_number,status FROM lobby_match WHERE lobby_id=? ORDER BY sequence_number")) {
            ps.setInt(1, lobbyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new MatchInfo(rs.getInt("id"), rs.getInt("lobby_id"),
                        rs.getInt("sequence_number"), rs.getString("status")));
            }
        } catch (SQLException e) { LOGGER.error("Could not load matches for lobby {}", lobbyId, e); }
        return result;
    }

    public static MatchInfo get(int matchId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT id,lobby_id,sequence_number,status FROM lobby_match WHERE id=?")) {
            ps.setInt(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new MatchInfo(rs.getInt("id"), rs.getInt("lobby_id"),
                        rs.getInt("sequence_number"), rs.getString("status")) : null;
            }
        } catch (SQLException e) { LOGGER.error("Could not load match {}", matchId, e); return null; }
    }

    public static List<MatchStatValue> getValues(int matchId) {
        String sql = "SELECT msv.id,msv.match_id,msv.stat_definition_id,gsd.name,gsd.scope,gsd.value_type," +
                "msv.user_id,u.username,msv.team_id,mt.name team_name,msv.value_text " +
                "FROM match_stat_value msv JOIN game_stat_definition gsd ON gsd.id=msv.stat_definition_id " +
                "LEFT JOIN user u ON u.id=msv.user_id LEFT JOIN match_team mt ON mt.id=msv.team_id " +
                "WHERE msv.match_id=? ORDER BY msv.id";
        List<MatchStatValue> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapValue(rs));
            }
        } catch (SQLException e) { LOGGER.error("Could not load values for match {}", matchId, e); }
        return result;
    }

    public static List<Integer> participantIds(int matchId) {
        List<Integer> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT user_id FROM match_participant WHERE match_id=? ORDER BY user_id")) {
            ps.setInt(1, matchId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(rs.getInt(1)); }
        } catch (SQLException e) { LOGGER.error("Could not load participants for match {}", matchId, e); }
        return result;
    }

    public static boolean isLobbyHost(int matchId, int userId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM lobby_match lm JOIN lobby l ON l.id=lm.lobby_id WHERE lm.id=? AND l.leader_id=?")) {
            ps.setInt(1, matchId); ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { return false; }
    }

    public static boolean confirm(int matchId, int userId, boolean confirmed, String comment) {
        MatchInfo match = get(matchId);
        if (match == null || !"PENDING_CONFIRMATION".equals(match.status()) || !isParticipant(matchId, userId))
            return false;
        String sql = "INSERT INTO match_confirmation (match_id,user_id,confirmed,comment) VALUES (?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE confirmed=VALUES(confirmed),comment=VALUES(comment),responded_at=CURRENT_TIMESTAMP";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, matchId); ps.setInt(2, userId); ps.setBoolean(3, confirmed); ps.setString(4, comment); ps.executeUpdate();
            refreshStatus(conn, matchId); return true;
        } catch (SQLException e) { LOGGER.error("Could not confirm match", e); return false; }
    }

    public static boolean beginCorrection(int matchId, int userId) {
        if (!isParticipant(matchId, userId) || isLobbyHost(matchId, userId)) return false;
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE lobby_match SET status='DISPUTED' WHERE id=? AND status='PENDING_CONFIRMATION'")) {
            ps.setInt(1, matchId); return ps.executeUpdate() == 1;
        } catch (SQLException e) { LOGGER.error("Could not begin correction for match {}", matchId, e); return false; }
    }

    public static boolean isParticipant(int matchId, int userId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM match_participant WHERE match_id=? AND user_id=?")) {
            ps.setInt(1, matchId); ps.setInt(2, userId); try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { return false; }
    }

    public static void submitForConfirmation(int matchId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE lobby_match SET status='PENDING_CONFIRMATION' WHERE id=?")) {
            ps.setInt(1, matchId); ps.executeUpdate();
        } catch (SQLException e) { LOGGER.error("Could not submit match", e); }
    }

    public static boolean markReady(int matchId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE lobby_match SET status='READY_FOR_CONFIRMATION' WHERE id=? AND status='DRAFT'")) {
            ps.setInt(1, matchId); return ps.executeUpdate() == 1;
        } catch (SQLException e) { LOGGER.error("Could not mark match {} ready", matchId, e); return false; }
    }

    public static boolean submitProposal(int matchId, int proposedBy, List<ProposedValue> values) {
        MatchInfo match = get(matchId);
        if (match == null || !"DISPUTED".equals(match.status())
                || !isParticipant(matchId, proposedBy) || isLobbyHost(matchId, proposedBy)) return false;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement delete = conn.prepareStatement("DELETE FROM match_stat_proposal WHERE match_id=?");
                 PreparedStatement insert = conn.prepareStatement(
                         "INSERT INTO match_stat_proposal (match_id,stat_value_id,proposed_by,value_text) VALUES (?,?,?,?)");
                 PreparedStatement status = conn.prepareStatement("UPDATE lobby_match SET status='HOST_REVIEW' WHERE id=?")) {
                delete.setInt(1, matchId); delete.executeUpdate();
                for (ProposedValue value : values) {
                    insert.setInt(1, matchId); insert.setInt(2, value.statValueId());
                    insert.setInt(3, proposedBy); insert.setString(4, value.value()); insert.addBatch();
                }
                insert.executeBatch(); status.setInt(1, matchId); status.executeUpdate(); conn.commit();
                return true;
            } catch (SQLException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { LOGGER.error("Could not submit proposal for match {}", matchId, e); return false; }
    }

    public static List<MatchStatProposal> getProposal(int matchId) {
        List<MatchStatProposal> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT p.stat_value_id,p.proposed_by,p.value_text,msv.value_text original_value " +
                        "FROM match_stat_proposal p JOIN match_stat_value msv ON msv.id=p.stat_value_id " +
                        "WHERE p.match_id=? ORDER BY p.stat_value_id")) {
            ps.setInt(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new MatchStatProposal(rs.getInt("stat_value_id"),
                        rs.getInt("proposed_by"), rs.getString("original_value"), rs.getString("value_text")));
            }
        } catch (SQLException e) { LOGGER.error("Could not load proposal for match {}", matchId, e); }
        return result;
    }

    public static boolean resolveProposal(int matchId, int hostId, boolean approved) {
        MatchInfo match = get(matchId);
        if (match == null || !"HOST_REVIEW".equals(match.status()) || !isLobbyHost(matchId, hostId)) return false;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement apply = conn.prepareStatement(
                    "UPDATE match_stat_value msv JOIN match_stat_proposal p ON p.stat_value_id=msv.id " +
                            "SET msv.value_text=p.value_text WHERE p.match_id=?");
                 PreparedStatement clear = conn.prepareStatement("DELETE FROM match_stat_proposal WHERE match_id=?");
                 PreparedStatement status = conn.prepareStatement("UPDATE lobby_match SET status=? WHERE id=?")) {
                if (approved) { apply.setInt(1, matchId); apply.executeUpdate(); }
                clear.setInt(1, matchId); clear.executeUpdate();
                status.setString(1, approved ? "CONFIRMED" : "REJECTED"); status.setInt(2, matchId);
                status.executeUpdate(); conn.commit(); return true;
            } catch (SQLException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { LOGGER.error("Could not resolve proposal for match {}", matchId, e); return false; }
    }

    public static boolean openEntryWindow(int lobbyId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO lobby_match_review (lobby_id,entry_deadline) " +
                        "SELECT id,TIMESTAMPADD(SECOND,3600,CURRENT_TIMESTAMP) FROM lobby " +
                        "WHERE id=? AND status='CLOSED'")) {
            ps.setInt(1, lobbyId); ps.executeUpdate(); return canAddMatch(lobbyId);
        } catch (SQLException e) { LOGGER.error("Could not open match entry window for lobby {}", lobbyId, e); return false; }
    }

    public static MatchReview getReview(int lobbyId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM lobby_match_review WHERE lobby_id=?")) {
            ps.setInt(1, lobbyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new MatchReview(rs.getInt("lobby_id"), rs.getString("status"),
                        rs.getTimestamp("entry_deadline"), rs.getTimestamp("review_requested_at"),
                        rs.getTimestamp("review_deadline"), rs.getInt("revision"),
                        rs.getBoolean("requires_host"), rs.getBoolean("entry_finished_manually"),
                        rs.getString("host_message_id")) : null;
            }
        } catch (SQLException e) { LOGGER.error("Could not load match review for lobby {}", lobbyId, e); return null; }
    }

    public static boolean canAddMatch(int lobbyId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM lobby_match_review r JOIN lobby l ON l.id=r.lobby_id " +
                        "WHERE r.lobby_id=? AND r.status='ENTRY' AND l.closed_at IS NOT NULL " +
                        "AND TIMESTAMPDIFF(SECOND,l.closed_at,CURRENT_TIMESTAMP)<3600")) {
            ps.setInt(1, lobbyId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            LOGGER.error("Could not check match entry window for lobby {}", lobbyId, e);
            return false;
        }
    }

    public static boolean recoverAutomaticallyClosedEntryWindow(int lobbyId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE lobby_match_review r JOIN lobby l ON l.id=r.lobby_id SET r.status='ENTRY'," +
                        "r.entry_deadline=TIMESTAMPADD(SECOND,3600,l.closed_at),r.review_requested_at=NULL," +
                        "r.review_deadline=NULL WHERE r.lobby_id=? AND r.status='COMPLETE' " +
                        "AND r.entry_finished_manually=FALSE AND l.closed_at IS NOT NULL " +
                        "AND TIMESTAMPDIFF(SECOND,l.closed_at,CURRENT_TIMESTAMP)<3600")) {
            ps.setInt(1, lobbyId); return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOGGER.error("Could not recover automatically closed match entry for lobby {}", lobbyId, e);
            return false;
        }
    }

    public static long entryDeadlineEpochSeconds(int lobbyId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT UNIX_TIMESTAMP(closed_at)+3600 FROM lobby WHERE id=? AND closed_at IS NOT NULL")) {
            ps.setInt(1, lobbyId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0L; }
        } catch (SQLException e) { return 0L; }
    }

    public static void storeHostMessage(int lobbyId, String messageId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE lobby_match_review SET host_message_id=? WHERE lobby_id=? AND status='ENTRY'")) {
            ps.setString(1, messageId); ps.setInt(2, lobbyId); ps.executeUpdate();
        } catch (SQLException e) { LOGGER.error("Could not store host match message for lobby {}", lobbyId, e); }
    }

    public static List<MatchReview> dueEntryReviews() {
        List<MatchReview> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT r.* FROM lobby_match_review r JOIN lobby l ON l.id=r.lobby_id " +
                        "WHERE r.status='ENTRY' AND l.closed_at IS NOT NULL " +
                        "AND TIMESTAMPDIFF(SECOND,l.closed_at,CURRENT_TIMESTAMP)>=3600");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(mapReview(rs));
        } catch (SQLException e) { LOGGER.error("Could not load due match entry reviews", e); }
        return result;
    }
    public static List<MatchReview> dueConfirmationReviews() { return dueReviews("REVIEW", "review_deadline"); }

    private static List<MatchReview> dueReviews(String status, String deadlineColumn) {
        List<MatchReview> result = new ArrayList<>();
        String sql = "SELECT * FROM lobby_match_review WHERE status=? AND " + deadlineColumn + "<=CURRENT_TIMESTAMP";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapReview(rs));
            }
        } catch (SQLException e) { LOGGER.error("Could not load due match reviews", e); }
        return result;
    }

    public static boolean beginLobbyReview(int lobbyId, boolean manuallyFinished) {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement review = conn.prepareStatement(
                    "UPDATE lobby_match_review SET status='REVIEW',review_requested_at=CURRENT_TIMESTAMP," +
                            "review_deadline=TIMESTAMPADD(SECOND,3600,CURRENT_TIMESTAMP),entry_finished_manually=? " +
                            "WHERE lobby_id=? AND status='ENTRY'");
                 PreparedStatement ready = conn.prepareStatement(
                         "UPDATE lobby_match SET status='PENDING_CONFIRMATION' WHERE lobby_id=? AND status='READY_FOR_CONFIRMATION'");
                 PreparedStatement drafts = conn.prepareStatement(
                         "UPDATE lobby_match SET status='REJECTED' WHERE lobby_id=? AND status='DRAFT'");
                 PreparedStatement states = conn.prepareStatement(
                         "INSERT IGNORE INTO match_review_state (match_id) SELECT id FROM lobby_match " +
                                 "WHERE lobby_id=? AND status='PENDING_CONFIRMATION'")) {
                review.setBoolean(1, manuallyFinished); review.setInt(2, lobbyId);
                if (review.executeUpdate() != 1) { conn.rollback(); return false; }
                ready.setInt(1, lobbyId); ready.executeUpdate();
                drafts.setInt(1, lobbyId); drafts.executeUpdate();
                states.setInt(1, lobbyId); states.executeUpdate();
                conn.commit(); return true;
            } catch (SQLException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { LOGGER.error("Could not begin lobby review {}", lobbyId, e); return false; }
    }

    private static MatchReview mapReview(ResultSet rs) throws SQLException {
        return new MatchReview(rs.getInt("lobby_id"), rs.getString("status"),
                rs.getTimestamp("entry_deadline"), rs.getTimestamp("review_requested_at"),
                rs.getTimestamp("review_deadline"), rs.getInt("revision"), rs.getBoolean("requires_host"),
                rs.getBoolean("entry_finished_manually"), rs.getString("host_message_id"));
    }

    public static List<Integer> reviewParticipants(int lobbyId) {
        List<Integer> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT DISTINCT mp.user_id FROM match_participant mp JOIN lobby_match lm ON lm.id=mp.match_id " +
                        "WHERE lm.lobby_id=? AND lm.status<>'REJECTED' ORDER BY mp.user_id")) {
            ps.setInt(1, lobbyId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(rs.getInt(1)); }
        } catch (SQLException e) { LOGGER.error("Could not load review participants for lobby {}", lobbyId, e); }
        return result;
    }

    public static void storeReviewMessage(int lobbyId, int userId, String messageId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO lobby_match_review_message (lobby_id,user_id,message_id) VALUES (?,?,?) " +
                        "ON DUPLICATE KEY UPDATE message_id=VALUES(message_id)")) {
            ps.setInt(1, lobbyId); ps.setInt(2, userId); ps.setString(3, messageId); ps.executeUpdate();
        } catch (SQLException e) { LOGGER.error("Could not store review message for lobby {} user {}", lobbyId, userId, e); }
    }

    public static List<ReviewMessage> getReviewMessages(int lobbyId) {
        List<ReviewMessage> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT lobby_id,user_id,message_id FROM lobby_match_review_message WHERE lobby_id=?")) {
            ps.setInt(1, lobbyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new ReviewMessage(rs.getInt(1), rs.getInt(2), rs.getString(3)));
            }
        } catch (SQLException e) { LOGGER.error("Could not load review messages for lobby {}", lobbyId, e); }
        return result;
    }

    public static void deleteReviewMessages(int lobbyId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM lobby_match_review_message WHERE lobby_id=?")) {
            ps.setInt(1, lobbyId); ps.executeUpdate();
        } catch (SQLException e) { LOGGER.error("Could not delete review message records for lobby {}", lobbyId, e); }
    }

    public static boolean confirmLobby(int lobbyId, int userId) {
        MatchReview review = getReview(lobbyId);
        if (review == null || !"REVIEW".equals(review.status()) || review.reviewDeadline() == null
                || !review.reviewDeadline().after(new Timestamp(System.currentTimeMillis()))) return false;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement pending = conn.prepareStatement(
                    "SELECT lm.id,mrs.revision FROM lobby_match lm " +
                            "JOIN match_review_state mrs ON mrs.match_id=lm.id JOIN lobby l ON l.id=lm.lobby_id " +
                            "WHERE lm.lobby_id=? AND lm.status='PENDING_CONFIRMATION' " +
                            "AND ((?=l.leader_id AND mrs.requires_host=TRUE) OR (?<>l.leader_id AND EXISTS " +
                            "(SELECT 1 FROM match_participant mp WHERE mp.match_id=lm.id AND mp.user_id=?)))");
                 PreparedStatement confirm = conn.prepareStatement(
                         "INSERT INTO match_review_confirmation (match_id,user_id,revision) VALUES (?,?,?) " +
                                 "ON DUPLICATE KEY UPDATE revision=VALUES(revision),confirmed_at=CURRENT_TIMESTAMP")) {
                pending.setInt(1, lobbyId); pending.setInt(2, userId); pending.setInt(3, userId); pending.setInt(4, userId);
                int count = 0;
                try (ResultSet rs = pending.executeQuery()) {
                    while (rs.next()) {
                        confirm.setInt(1, rs.getInt(1)); confirm.setInt(2, userId); confirm.setInt(3, rs.getInt(2));
                        confirm.addBatch(); count++;
                    }
                }
                if (count == 0) { conn.rollback(); return false; }
                confirm.executeBatch();
                refreshReviewMatches(conn, lobbyId);
                conn.commit(); return true;
            } catch (SQLException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { LOGGER.error("Could not confirm lobby review {}", lobbyId, e); return false; }
    }

    public static boolean hasPendingReviewForUser(int lobbyId, int userId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM lobby_match lm JOIN match_review_state mrs ON mrs.match_id=lm.id " +
                        "JOIN lobby l ON l.id=lm.lobby_id " +
                        "LEFT JOIN match_review_confirmation mrc ON mrc.match_id=lm.id AND mrc.user_id=? " +
                        "AND mrc.revision=mrs.revision WHERE lm.lobby_id=? AND lm.status='PENDING_CONFIRMATION' " +
                        "AND ((?=l.leader_id AND mrs.requires_host=TRUE) OR (?<>l.leader_id AND EXISTS " +
                        "(SELECT 1 FROM match_participant mp WHERE mp.match_id=lm.id AND mp.user_id=?))) " +
                        "AND mrc.user_id IS NULL LIMIT 1")) {
            ps.setInt(1, userId); ps.setInt(2, lobbyId); ps.setInt(3, userId);
            ps.setInt(4, userId); ps.setInt(5, userId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { LOGGER.error("Could not load pending reviews for lobby {}", lobbyId, e); return false; }
    }

    public static boolean isReviewRelevant(int matchId, int userId) {
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM lobby_match lm JOIN lobby l ON l.id=lm.lobby_id " +
                        "JOIN match_review_state mrs ON mrs.match_id=lm.id WHERE lm.id=? AND " +
                        "((l.leader_id=? AND mrs.requires_host=TRUE) OR (l.leader_id<>? AND EXISTS " +
                        "(SELECT 1 FROM match_participant mp WHERE mp.match_id=lm.id AND mp.user_id=?)))")) {
            ps.setInt(1, matchId); ps.setInt(2, userId); ps.setInt(3, userId); ps.setInt(4, userId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { LOGGER.error("Could not check review relevance for match {}", matchId, e); return false; }
    }

    public static List<ProfileNotification> claimProfileNotifications(int lobbyId) {
        List<ProfileNotification> result = new ArrayList<>();
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement candidates = conn.prepareStatement(
                    "SELECT DISTINCT mp.user_id FROM match_participant mp JOIN lobby_match lm ON lm.id=mp.match_id " +
                            "LEFT JOIN lobby_match_profile_notification n ON n.lobby_id=lm.lobby_id AND n.user_id=mp.user_id " +
                            "WHERE lm.lobby_id=? AND lm.status<>'REJECTED' AND n.user_id IS NULL AND NOT EXISTS " +
                            "(SELECT 1 FROM match_participant mp2 JOIN lobby_match lm2 ON lm2.id=mp2.match_id " +
                            "WHERE lm2.lobby_id=lm.lobby_id AND mp2.user_id=mp.user_id " +
                            "AND lm2.status NOT IN ('CONFIRMED','REJECTED'))");
                 PreparedStatement claim = conn.prepareStatement(
                         "INSERT IGNORE INTO lobby_match_profile_notification (lobby_id,user_id) VALUES (?,?)");
                 PreparedStatement matches = conn.prepareStatement(
                         "SELECT lm.sequence_number FROM lobby_match lm JOIN match_participant mp ON mp.match_id=lm.id " +
                                 "WHERE lm.lobby_id=? AND mp.user_id=? AND lm.status='CONFIRMED' ORDER BY lm.sequence_number")) {
                candidates.setInt(1, lobbyId);
                List<Integer> userIds = new ArrayList<>();
                try (ResultSet rs = candidates.executeQuery()) { while (rs.next()) userIds.add(rs.getInt(1)); }
                for (int userId : userIds) {
                    claim.setInt(1, lobbyId); claim.setInt(2, userId);
                    if (claim.executeUpdate() != 1) continue;
                    matches.setInt(1, lobbyId); matches.setInt(2, userId);
                    List<Integer> sequences = new ArrayList<>();
                    try (ResultSet rs = matches.executeQuery()) { while (rs.next()) sequences.add(rs.getInt(1)); }
                    if (!sequences.isEmpty()) result.add(new ProfileNotification(userId, sequences));
                }
                conn.commit(); return result;
            } catch (SQLException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) {
            LOGGER.error("Could not claim profile notifications for lobby {}", lobbyId, e);
            return List.of();
        }
    }

    public static boolean applyCorrection(int lobbyId, int matchId, int userId, List<ProposedValue> values) {
        MatchReview review = getReview(lobbyId);
        if (review == null || !"REVIEW".equals(review.status()) || review.reviewDeadline() == null
                || !review.reviewDeadline().after(new Timestamp(System.currentTimeMillis()))
                || !isParticipant(matchId, userId) || isLobbyHost(matchId, userId)) return false;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement owns = conn.prepareStatement(
                    "SELECT 1 FROM match_stat_value msv JOIN lobby_match lm ON lm.id=msv.match_id " +
                            "WHERE msv.id=? AND msv.match_id=? AND lm.lobby_id=? AND lm.status='PENDING_CONFIRMATION'");
                 PreparedStatement update = conn.prepareStatement(
                    "UPDATE match_stat_value msv JOIN lobby_match lm ON lm.id=msv.match_id " +
                            "SET msv.value_text=? WHERE msv.id=? AND msv.match_id=? AND lm.lobby_id=? " +
                            "AND lm.status='PENDING_CONFIRMATION'");
                 PreparedStatement state = conn.prepareStatement(
                         "UPDATE match_review_state SET revision=revision+1,requires_host=TRUE WHERE match_id=?");
                 PreparedStatement revision = conn.prepareStatement(
                         "UPDATE lobby_match_review SET revision=revision+1,requires_host=TRUE WHERE lobby_id=? AND status='REVIEW'");
                 PreparedStatement clear = conn.prepareStatement(
                         "DELETE FROM match_review_confirmation WHERE match_id=?");
                 PreparedStatement current = conn.prepareStatement(
                         "SELECT revision FROM match_review_state WHERE match_id=?");
                 PreparedStatement proposer = conn.prepareStatement(
                         "INSERT INTO match_review_confirmation (match_id,user_id,revision) VALUES (?,?,?) " +
                                 "ON DUPLICATE KEY UPDATE revision=VALUES(revision),confirmed_at=CURRENT_TIMESTAMP")) {
                for (ProposedValue value : values) {
                    owns.setInt(1, value.statValueId()); owns.setInt(2, matchId); owns.setInt(3, lobbyId);
                    try (ResultSet rs = owns.executeQuery()) { if (!rs.next()) { conn.rollback(); return false; } }
                    update.setString(1, value.value()); update.setInt(2, value.statValueId());
                    update.setInt(3, matchId); update.setInt(4, lobbyId);
                    update.executeUpdate();
                }
                state.setInt(1, matchId);
                if (state.executeUpdate() != 1) { conn.rollback(); return false; }
                revision.setInt(1, lobbyId); revision.executeUpdate();
                clear.setInt(1, matchId); clear.executeUpdate();
                current.setInt(1, matchId);
                try (ResultSet rs = current.executeQuery()) {
                    if (!rs.next()) { conn.rollback(); return false; }
                    proposer.setInt(1, matchId); proposer.setInt(2, userId); proposer.setInt(3, rs.getInt(1));
                    proposer.executeUpdate();
                }
                conn.commit(); return true;
            } catch (SQLException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { LOGGER.error("Could not apply correction for match {}", matchId, e); return false; }
    }

    public static boolean completeLobbyReview(int lobbyId) {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement matches = conn.prepareStatement(
                    "UPDATE lobby_match SET status='CONFIRMED' WHERE lobby_id=? AND status='PENDING_CONFIRMATION'");
                 PreparedStatement review = conn.prepareStatement(
                         "UPDATE lobby_match_review SET status='COMPLETE' WHERE lobby_id=? AND status='REVIEW'")) {
                matches.setInt(1, lobbyId); matches.executeUpdate(); review.setInt(1, lobbyId);
                if (review.executeUpdate() != 1) { conn.rollback(); return false; }
                conn.commit(); return true;
            } catch (SQLException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { LOGGER.error("Could not complete lobby review {}", lobbyId, e); return false; }
    }

    private static void refreshReviewMatches(Connection conn, int lobbyId) throws SQLException {
        List<Integer> completed = new ArrayList<>();
        try (PreparedStatement matches = conn.prepareStatement(
                "SELECT lm.id,mrs.revision,mrs.requires_host,l.leader_id FROM lobby_match lm " +
                        "JOIN match_review_state mrs ON mrs.match_id=lm.id JOIN lobby l ON l.id=lm.lobby_id " +
                        "WHERE lm.lobby_id=? AND lm.status='PENDING_CONFIRMATION'")) {
            matches.setInt(1, lobbyId);
            try (ResultSet rs = matches.executeQuery()) {
                while (rs.next()) {
                    int matchId = rs.getInt(1), revision = rs.getInt(2), hostId = rs.getInt(4);
                    Set<Integer> required = new HashSet<>();
                    try (PreparedStatement participants = conn.prepareStatement(
                            "SELECT user_id FROM match_participant WHERE match_id=?")) {
                        participants.setInt(1, matchId);
                        try (ResultSet users = participants.executeQuery()) { while (users.next()) required.add(users.getInt(1)); }
                    }
                    if (rs.getBoolean(3)) required.add(hostId); else required.remove(hostId);
                    Set<Integer> confirmed = new HashSet<>();
                    try (PreparedStatement confirmations = conn.prepareStatement(
                            "SELECT user_id FROM match_review_confirmation WHERE match_id=? AND revision=?")) {
                        confirmations.setInt(1, matchId); confirmations.setInt(2, revision);
                        try (ResultSet users = confirmations.executeQuery()) { while (users.next()) confirmed.add(users.getInt(1)); }
                    }
                    if (confirmed.containsAll(required)) completed.add(matchId);
                }
            }
        }
        try (PreparedStatement confirm = conn.prepareStatement(
                "UPDATE lobby_match SET status='CONFIRMED' WHERE id=? AND status='PENDING_CONFIRMATION'")) {
            for (int matchId : completed) { confirm.setInt(1, matchId); confirm.addBatch(); }
            confirm.executeBatch();
        }
        try (PreparedStatement pending = conn.prepareStatement(
                "SELECT 1 FROM lobby_match WHERE lobby_id=? AND status='PENDING_CONFIRMATION' LIMIT 1")) {
            pending.setInt(1, lobbyId);
            try (ResultSet rs = pending.executeQuery()) {
                if (!rs.next()) try (PreparedStatement complete = conn.prepareStatement(
                        "UPDATE lobby_match_review SET status='COMPLETE' WHERE lobby_id=? AND status='REVIEW'")) {
                    complete.setInt(1, lobbyId); complete.executeUpdate();
                }
            }
        }
    }

    private static void refreshStatus(Connection conn, int matchId) throws SQLException {
        String sql = "SELECT COUNT(*) participants,COUNT(mc.user_id) responses," +
                "SUM(CASE WHEN mc.confirmed=FALSE THEN 1 ELSE 0 END) rejects FROM match_participant mp " +
                "LEFT JOIN match_confirmation mc ON mc.match_id=mp.match_id AND mc.user_id=mp.user_id WHERE mp.match_id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next(); int participants = rs.getInt("participants"); int responses = rs.getInt("responses"); int rejects = rs.getInt("rejects");
                String status = rejects > 0 ? "DISPUTED" : responses == participants && participants > 0 ? "CONFIRMED" : "PENDING_CONFIRMATION";
                try (PreparedStatement update = conn.prepareStatement("UPDATE lobby_match SET status=? WHERE id=?")) {
                    update.setString(1, status); update.setInt(2, matchId); update.executeUpdate();
                }
            }
        }
    }

    private static MatchStatValue mapValue(ResultSet rs) throws SQLException {
        Integer userId = rs.getObject("user_id") == null ? null : rs.getInt("user_id");
        Integer teamId = rs.getObject("team_id") == null ? null : rs.getInt("team_id");
        return new MatchStatValue(rs.getInt("id"), rs.getInt("match_id"), rs.getInt("stat_definition_id"),
                rs.getString("name"), rs.getString("scope"), rs.getString("value_type"), userId,
                rs.getString("username"), teamId, rs.getString("team_name"), rs.getString("value_text"));
    }

    public record MatchInfo(int id, int lobbyId, int sequenceNumber, String status) {}
    public record MatchStatValue(int id, int matchId, int definitionId, String name, String scope,
                                 String valueType, Integer userId, String username, Integer teamId,
                                 String teamName, String value) {}
    public record ProposedValue(int statValueId, String value) {}
    public record MatchStatProposal(int statValueId, int proposedBy, String originalValue, String proposedValue) {}
    public record MatchReview(int lobbyId, String status, Timestamp entryDeadline, Timestamp reviewRequestedAt,
                              Timestamp reviewDeadline, int revision, boolean requiresHost,
                              boolean entryFinishedManually, String hostMessageId) {}
    public record ReviewMessage(int lobbyId, int userId, String messageId) {}
    public record ProfileNotification(int userId, List<Integer> matchSequences) {}
}
