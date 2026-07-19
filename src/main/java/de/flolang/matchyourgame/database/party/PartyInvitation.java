package de.flolang.matchyourgame.database.party;

public record PartyInvitation(int id, int partyId, int inviterId, int inviteeId, Status status) {
    public enum Status { PENDING, ACCEPTED, DECLINED }
}
