package de.flolang.matchyourgame.database.party;

import java.util.List;

public record PartyObject(int id, int hostUserId, List<Integer> memberIds) {
}
