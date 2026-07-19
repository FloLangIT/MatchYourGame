package de.flolang.matchyourgame.manager.party;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.friend.FriendRepository;
import de.flolang.matchyourgame.database.party.PartyInvitation;
import de.flolang.matchyourgame.database.party.PartyInvitationRepository;
import de.flolang.matchyourgame.database.party.PartyObject;
import de.flolang.matchyourgame.database.party.PartyRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.ManagementMessageUpdater;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

import java.util.Map;
import java.time.Duration;
import java.time.Instant;

public final class PartyService {
    public static final Duration INACTIVITY_TIMEOUT = Duration.ofHours(1);

    public PartyObject create(int hostUserId) {
        PartyObject party = PartyRepository.create(hostUserId);
        if (party != null) ManagementMessageUpdater.refreshFriendActivity(hostUserId);
        return party;
    }

    public boolean inviteFriend(int hostUserId, int friendUserId) {
        PartyObject party = PartyRepository.getForUser(hostUserId);
        if (party == null || party.hostUserId() != hostUserId
                || !FriendRepository.areFriends(hostUserId, friendUserId)
                || PartyRepository.getForUser(friendUserId) != null) return false;
        PartyInvitation invitation = PartyInvitationRepository.create(party.id(), hostUserId, friendUserId);
        UserObject host = UserController.get(hostUserId);
        UserObject friend = UserController.get(friendUserId);
        if (invitation == null || host == null || friend == null || Main.jda == null) return false;
        Main.jda.retrieveUserById(friend.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
                dm.sendMessageEmbeds(new EmbedCreator().setTitle(t(friendUserId, "Party.Invitation.Title"))
                                .setDescription(t(friendUserId, "Party.Invitation.Description",
                                        Map.of("%host%", host.getUsername()))).build())
                        .setComponents(ActionRow.of(
                                Button.success("partyInviteAccept-" + invitation.id(), t(friendUserId, "Party.Invitation.Accept")),
                                Button.danger("partyInviteDecline-" + invitation.id(), t(friendUserId, "Party.Invitation.Decline")),
                                Button.danger("delete", t(friendUserId, "General.Button.DeleteMessage"))))
                        .queue()));
        return true;
    }

    public boolean acceptInvitation(int invitationId, int inviteeId) {
        PartyInvitation invitation = PartyInvitationRepository.get(invitationId);
        if (invitation == null || invitation.inviteeId() != inviteeId
                || invitation.status() != PartyInvitation.Status.PENDING
                || PartyRepository.getForUser(inviteeId) != null) return false;
        PartyObject party = PartyRepository.get(invitation.partyId());
        if (party == null || party.hostUserId() != invitation.inviterId()
                || !FriendRepository.areFriends(invitation.inviterId(), inviteeId)) return false;
        if (!PartyRepository.addMember(party.id(), inviteeId)) return false;
        boolean accepted = PartyInvitationRepository.respond(invitationId, PartyInvitation.Status.ACCEPTED);
        if (accepted) {
            java.util.List<Integer> affected = new java.util.ArrayList<>(party.memberIds());
            affected.add(inviteeId);
            ManagementMessageUpdater.refreshPartyState(affected);
            notifyAccepted(invitation.inviterId(), inviteeId);
        }
        return accepted;
    }

    public boolean declineInvitation(int invitationId, int inviteeId) {
        PartyInvitation invitation = PartyInvitationRepository.get(invitationId);
        return invitation != null && invitation.inviteeId() == inviteeId
                && invitation.status() == PartyInvitation.Status.PENDING
                && PartyInvitationRepository.respond(invitationId, PartyInvitation.Status.DECLINED);
    }

    public boolean leave(int userId) {
        PartyObject party = PartyRepository.getForUser(userId);
        if (party == null) return false;
        boolean left = PartyRepository.leaveAndTransferHost(party.id(), userId);
        if (left) ManagementMessageUpdater.refreshPartyState(party.memberIds());
        return left;
    }

    public boolean kick(int hostUserId, int targetUserId) {
        PartyObject party = PartyRepository.getForUser(hostUserId);
        boolean kicked = party != null && party.hostUserId() == hostUserId && targetUserId != hostUserId
                && party.memberIds().contains(targetUserId)
                && PartyRepository.removeMember(party.id(), targetUserId);
        if (kicked) ManagementMessageUpdater.refreshPartyState(party.memberIds());
        return kicked;
    }

    public void processInactiveParties() {
        Instant cutoff = Instant.now().minus(INACTIVITY_TIMEOUT);
        for (int partyId : PartyRepository.inactivePartyIds(cutoff)) {
            PartyObject party = PartyRepository.get(partyId);
            if (party != null && PartyRepository.disbandIfInactive(partyId, cutoff))
                ManagementMessageUpdater.refreshPartyState(party.memberIds());
        }
    }

    private static String t(int userId, String key) {
        return LanguageManager.getMessageForUser(key, userId);
    }

    private static String t(int userId, String key, Map<String, String> replacements) {
        return LanguageManager.getMessageForUser(key, userId, replacements);
    }

    private static void notifyAccepted(int hostUserId, int inviteeId) {
        UserObject host = UserController.get(hostUserId);
        UserObject invitee = UserController.get(inviteeId);
        if (host == null || invitee == null || Main.jda == null) return;
        Main.jda.retrieveUserById(host.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
                dm.sendMessageEmbeds(new EmbedCreator()
                                .setTitle(t(hostUserId, "Party.Invitation.AcceptedNotification.Title"))
                                .setDescription(t(hostUserId, "Party.Invitation.AcceptedNotification.Description",
                                        Map.of("%user%", invitee.getUsername()))).build())
                        .setComponents(ActionRow.of(Button.danger("delete",
                                t(hostUserId, "General.Button.DeleteMessage"))))
                        .queue()));
    }
}
