package de.flolang.matchyourgame.database.review;

import de.flolang.matchyourgame.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ReviewRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewRepository.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private ReviewRepository() {}

    public static List<ReviewAssignment> createRandomAssignments(int lobbyId, List<Integer> memberIds) {
        List<Integer> members = memberIds.stream().distinct().toList();
        if (members.size() < 2) return List.of();
        List<ReviewAssignment> assignments = new ArrayList<>();
        String sql = "INSERT IGNORE INTO review_assignment (lobby_id,reviewer_user_id,target_user_id) VALUES (?,?,?)";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            Map<Integer, Integer> targets = selectTargets(members, reviewedTargets(conn, members));
            for (Map.Entry<Integer, Integer> selected : targets.entrySet()) {
                int reviewer = selected.getKey();
                int target = selected.getValue();
                ps.setInt(1, lobbyId); ps.setInt(2, reviewer); ps.setInt(3, target); ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) assignments.add(new ReviewAssignment(keys.getInt(1), lobbyId, reviewer, target, false));
                }
            }
        } catch (SQLException e) { LOGGER.error("Could not create review assignments", e); }
        return assignments;
    }

    public static ReviewAssignment get(int assignmentId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM review_assignment WHERE id=?")) {
            ps.setInt(1, assignmentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new ReviewAssignment(rs.getInt("id"), rs.getInt("lobby_id"),
                        rs.getInt("reviewer_user_id"), rs.getInt("target_user_id"), rs.getTimestamp("completed_at") != null) : null;
            }
        } catch (SQLException e) { LOGGER.error("Could not load review assignment", e); return null; }
    }

    public static boolean submit(int assignmentId, int reviewerId, int behavior, int teamplay,
                                 int reliability, String privateFeedback) {
        if (!stars(behavior) || !stars(teamplay) || !stars(reliability)) return false;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int targetId;
                try (PreparedStatement assignment = conn.prepareStatement(
                        "SELECT reviewer_user_id,target_user_id,completed_at FROM review_assignment WHERE id=?")) {
                    assignment.setInt(1, assignmentId);
                    try (ResultSet rs = assignment.executeQuery()) {
                        if (!rs.next() || rs.getInt("reviewer_user_id") != reviewerId
                                || rs.getTimestamp("completed_at") != null) {
                            conn.rollback(); return false;
                        }
                        targetId = rs.getInt("target_user_id");
                    }
                }
                try (PreparedStatement pairLock = conn.prepareStatement(
                        "SELECT id,completed_at FROM review_assignment " +
                                "WHERE reviewer_user_id=? AND target_user_id=? ORDER BY id FOR UPDATE")) {
                    pairLock.setInt(1, reviewerId); pairLock.setInt(2, targetId);
                    boolean currentPending = false;
                    try (ResultSet rows = pairLock.executeQuery()) {
                        while (rows.next()) {
                            if (rows.getInt("id") == assignmentId)
                                currentPending = rows.getTimestamp("completed_at") == null;
                        }
                    }
                    if (!currentPending) { conn.rollback(); return false; }
                }
                try (PreparedStatement removePrevious = conn.prepareStatement(
                        "DELETE pr FROM player_review pr " +
                                "JOIN review_assignment previous_assignment ON previous_assignment.id=pr.assignment_id " +
                                "WHERE previous_assignment.reviewer_user_id=? " +
                                "AND previous_assignment.target_user_id=?")) {
                    removePrevious.setInt(1, reviewerId); removePrevious.setInt(2, targetId);
                    removePrevious.executeUpdate();
                }
                try (PreparedStatement review = conn.prepareStatement(
                        "INSERT INTO player_review (assignment_id,behavior_stars,teamplay_stars,reliability_stars,private_feedback) VALUES (?,?,?,?,?)")) {
                    review.setInt(1, assignmentId); review.setInt(2, behavior); review.setInt(3, teamplay);
                    review.setInt(4, reliability); review.setString(5, privateFeedback); review.executeUpdate();
                }
                try (PreparedStatement complete = conn.prepareStatement(
                        "UPDATE review_assignment SET completed_at=CURRENT_TIMESTAMP WHERE id=?")) {
                    complete.setInt(1, assignmentId); complete.executeUpdate();
                }
                conn.commit(); return true;
            } catch (SQLException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { LOGGER.error("Could not submit review", e); return false; }
    }

    public static boolean moderate(int assignmentId, int moderationStars) {
        if (!stars(moderationStars)) return false;
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE player_review SET moderation_stars=?,moderated_at=CURRENT_TIMESTAMP WHERE assignment_id=?")) {
            ps.setInt(1, moderationStars); ps.setInt(2, assignmentId); return ps.executeUpdate() == 1;
        } catch (SQLException e) { LOGGER.error("Could not moderate review", e); return false; }
    }

    public static Double averageRating(int userId) {
        String sql = "SELECT AVG((pr.behavior_stars+pr.teamplay_stars+pr.reliability_stars+" +
                "COALESCE(pr.moderation_stars,(pr.behavior_stars+pr.teamplay_stars+pr.reliability_stars)/3.0))/4.0) rating " +
                "FROM review_assignment ra JOIN player_review pr ON pr.assignment_id=ra.id " +
                "WHERE ra.target_user_id=? AND ra.completed_at IS NOT NULL";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                double value = rs.getDouble("rating");
                return rs.wasNull() ? null : value;
            }
        } catch (SQLException e) { LOGGER.error("Could not calculate average rating for user {}", userId, e); return null; }
    }

    public static List<ModerationReview> pendingModeration() {
        List<ModerationReview> result = new ArrayList<>();
        String sql = "SELECT ra.id,ra.lobby_id,ra.target_user_id,pr.behavior_stars,pr.teamplay_stars," +
                "pr.reliability_stars,pr.private_feedback FROM review_assignment ra " +
                "JOIN player_review pr ON pr.assignment_id=ra.id WHERE pr.moderation_stars IS NULL " +
                "AND pr.private_feedback IS NOT NULL AND pr.private_feedback<>'' ORDER BY pr.created_at";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(new ModerationReview(rs.getInt("id"), rs.getInt("lobby_id"),
                    rs.getInt("target_user_id"), rs.getInt("behavior_stars"), rs.getInt("teamplay_stars"),
                    rs.getInt("reliability_stars"), rs.getString("private_feedback")));
        } catch (SQLException e) { LOGGER.error("Could not load moderation reviews", e); }
        return result;
    }

    public static List<ReceivedReview> receivedBy(int targetUserId) {
        List<ReceivedReview> result = new ArrayList<>();
        String sql = "SELECT ra.id,ra.lobby_id,ra.reviewer_user_id,u.username,pr.behavior_stars," +
                "pr.teamplay_stars,pr.reliability_stars,pr.moderation_stars,pr.private_feedback,ra.completed_at " +
                "FROM review_assignment ra JOIN player_review pr ON pr.assignment_id=ra.id " +
                "LEFT JOIN user u ON u.id=ra.reviewer_user_id WHERE ra.target_user_id=? AND ra.completed_at IS NOT NULL " +
                "ORDER BY ra.completed_at DESC,ra.id DESC";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, targetUserId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new ReceivedReview(rs.getInt("id"), rs.getInt("lobby_id"),
                        rs.getInt("reviewer_user_id"), rs.getString("username"), rs.getInt("behavior_stars"),
                        rs.getInt("teamplay_stars"), rs.getInt("reliability_stars"),
                        (Integer) rs.getObject("moderation_stars"), rs.getString("private_feedback"),
                        rs.getTimestamp("completed_at")));
            }
        } catch (SQLException e) { LOGGER.error("Could not load received reviews for user {}", targetUserId, e); }
        return result;
    }

    public record ReceivedReview(int assignmentId, int lobbyId, int reviewerUserId, String reviewerUsername,
                                 int behaviorStars, int teamplayStars, int reliabilityStars,
                                 Integer moderationStars, String privateFeedback, Timestamp completedAt) {}

    public record ModerationReview(int assignmentId, int lobbyId, int targetUserId, int behaviorStars,
                                   int teamplayStars, int reliabilityStars, String privateFeedback) {}

    private static Map<Integer, Set<Integer>> reviewedTargets(Connection conn, List<Integer> reviewers)
            throws SQLException {
        Map<Integer, Set<Integer>> result = new HashMap<>();
        reviewers.forEach(reviewer -> result.put(reviewer, new HashSet<>()));
        String placeholders = String.join(",", Collections.nCopies(reviewers.size(), "?"));
        String sql = "SELECT DISTINCT ra.reviewer_user_id,ra.target_user_id FROM review_assignment ra " +
                "JOIN player_review pr ON pr.assignment_id=ra.id WHERE ra.reviewer_user_id IN (" + placeholders + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < reviewers.size(); i++) ps.setInt(i + 1, reviewers.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    result.computeIfAbsent(rs.getInt("reviewer_user_id"), ignored -> new HashSet<>())
                            .add(rs.getInt("target_user_id"));
            }
        }
        return result;
    }

    static Map<Integer, Integer> selectTargets(List<Integer> memberIds,
                                               Map<Integer, Set<Integer>> reviewedTargets) {
        final int repeatedCost = 1_000_000;
        final int selfCost = 100_000_000;
        int size = memberIds.size();
        int[][] costs = new int[size][size];
        for (int reviewerIndex = 0; reviewerIndex < size; reviewerIndex++) {
            int reviewer = memberIds.get(reviewerIndex);
            Set<Integer> reviewed = reviewedTargets.getOrDefault(reviewer, Set.of());
            for (int targetIndex = 0; targetIndex < size; targetIndex++) {
                int target = memberIds.get(targetIndex);
                costs[reviewerIndex][targetIndex] = reviewer == target ? selfCost
                        : (reviewed.contains(target) ? repeatedCost : 0) + RANDOM.nextInt(1000);
            }
        }

        int[] reviewerPotential = new int[size + 1];
        int[] targetPotential = new int[size + 1];
        int[] targetReviewer = new int[size + 1];
        int[] previousTarget = new int[size + 1];
        for (int reviewer = 1; reviewer <= size; reviewer++) {
            targetReviewer[0] = reviewer;
            int currentTarget = 0;
            int[] minimum = new int[size + 1];
            java.util.Arrays.fill(minimum, Integer.MAX_VALUE);
            boolean[] used = new boolean[size + 1];
            do {
                used[currentTarget] = true;
                int currentReviewer = targetReviewer[currentTarget];
                int delta = Integer.MAX_VALUE;
                int nextTarget = 0;
                for (int target = 1; target <= size; target++) {
                    if (used[target]) continue;
                    int reducedCost = costs[currentReviewer - 1][target - 1]
                            - reviewerPotential[currentReviewer] - targetPotential[target];
                    if (reducedCost < minimum[target]) {
                        minimum[target] = reducedCost;
                        previousTarget[target] = currentTarget;
                    }
                    if (minimum[target] < delta) {
                        delta = minimum[target];
                        nextTarget = target;
                    }
                }
                for (int target = 0; target <= size; target++) {
                    if (used[target]) {
                        reviewerPotential[targetReviewer[target]] += delta;
                        targetPotential[target] -= delta;
                    } else {
                        minimum[target] -= delta;
                    }
                }
                currentTarget = nextTarget;
            } while (targetReviewer[currentTarget] != 0);
            do {
                int targetBefore = previousTarget[currentTarget];
                targetReviewer[currentTarget] = targetReviewer[targetBefore];
                currentTarget = targetBefore;
            } while (currentTarget != 0);
        }

        Map<Integer, Integer> selected = new java.util.LinkedHashMap<>();
        for (int target = 1; target <= size; target++)
            selected.put(memberIds.get(targetReviewer[target] - 1), memberIds.get(target - 1));
        return selected;
    }

    private static boolean stars(int value) { return value >= 1 && value <= 5; }
}
