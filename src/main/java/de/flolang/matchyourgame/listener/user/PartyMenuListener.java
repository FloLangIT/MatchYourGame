package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.UserControlManager;
import de.flolang.matchyourgame.manager.party.PartyService;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.concurrent.TimeUnit;

public final class PartyMenuListener extends ListenerAdapter {
    private final PartyService parties = new PartyService();

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        if (event.getComponentId().equals("partyKickMember")) {
            boolean kicked = parties.kick(user.getId(), Integer.parseInt(event.getValues().getFirst()));
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadPartyPage(0,
                    kicked ? "Party.Kick.Success" : "Party.Kick.Failed");
            return;
        }
        if (!event.getComponentId().startsWith("partyFriendSelect-")) return;
        int page = Integer.parseInt(event.getComponentId().substring("partyFriendSelect-".length()));
        int friendId = Integer.parseInt(event.getValues().getFirst());
        boolean invited = parties.inviteFriend(user.getId(), friendId);
        event.deferEdit().queue();
        new UserControlManager(event.getMessage(), user).loadPartyPage(page,
                invited ? "Party.Invite.Success" : "Party.Invite.Failed");
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        if (id.startsWith("partyPage-")) {
            int page = Integer.parseInt(id.substring("partyPage-".length()));
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadPartyPage(page);
        } else if (id.startsWith("partyInviteAccept-")) {
            boolean accepted = parties.acceptInvitation(suffix(id), user.getId());
            var confirmation = event.editMessageEmbeds(result(user.getId(), accepted
                            ? "Party.Invitation.Accepted" : "Party.Invitation.NotAvailable"))
                    .setComponents();
            if (accepted) confirmation.queue(hook -> hook.deleteOriginal().queueAfter(5, TimeUnit.SECONDS));
            else confirmation.queue();
        } else if (id.startsWith("partyInviteDecline-")) {
            boolean declined = parties.declineInvitation(suffix(id), user.getId());
            event.editMessageEmbeds(result(user.getId(), declined ? "Party.Invitation.Declined" : "Party.Invitation.NotAvailable"))
                    .setComponents().queue();
        }
    }

    private static net.dv8tion.jda.api.entities.MessageEmbed result(int userId, String key) {
        return new EmbedCreator().setDescription(LanguageManager.getMessageForUser(key, userId)).build();
    }

    private static int suffix(String id) { return Integer.parseInt(id.substring(id.lastIndexOf('-') + 1)); }
}
