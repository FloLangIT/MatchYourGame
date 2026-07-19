package de.flolang.matchyourgame.database.review;

public record ReviewAssignment(int id, int lobbyId, int reviewerUserId, int targetUserId, boolean completed) {
}
