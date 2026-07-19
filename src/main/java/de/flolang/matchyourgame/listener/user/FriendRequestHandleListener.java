package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.database.friend.FriendObject;
import de.flolang.matchyourgame.database.friend.FriendRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.Language;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.FriendRequestManager;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.HashMap;

public class FriendRequestHandleListener extends ListenerAdapter {
    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String customId = event.getComponentId();
        boolean accept = customId.startsWith("friendAccept-");
        boolean deny = customId.startsWith("friendDeny-");
        boolean ignore = customId.startsWith("friendIgnore-");
        if (!accept && !deny && !ignore) return;

        UserObject receiver = UserController.get(event.getUser().getIdLong());
        if (receiver == null) {
            event.replyEmbeds(new EmbedCreator().setDescription(LanguageManager.getMessageByLanguage(
                    "FriendRequest.Interact.NoAccount", Language.EN)).build()).setEphemeral(true).queue();
            return;
        }

        int requesterId;
        try {
            requesterId = Integer.parseInt(customId.substring(customId.indexOf('-') + 1));
        } catch (NumberFormatException exception) {
            reply(receiver, event, "FriendRequest.Interact.NoFriendRequest");
            return;
        }

        FriendObject friendship = FriendRepository.get(requesterId, receiver.getId());
        if (friendship == null || friendship.getReceiverID() != receiver.getId()) {
            reply(receiver, event, "FriendRequest.Interact.NoFriendRequest");
            return;
        }
        if (friendship.getAccepted_at() != null) {
            reply(receiver, event, "FriendRequest.Interact.AlreadyAccepted");
            return;
        }
        UserObject requester = UserController.get(requesterId);
        if (requester == null) {
            reply(receiver, event, "FriendRequest.Interact.NoFriendRequest");
            return;
        }

        FriendRequestManager manager = new FriendRequestManager(friendship);
        if (accept) manager.acceptFriendRequest();
        else if (deny) manager.denyFriendRequest();
        HashMap<String, String> replacements = new HashMap<>();
        replacements.put("%requester%", requester.getUsername());
        String resultKey = accept ? "FriendRequest.ConfirmAcceptToReceiver"
                : deny ? "FriendRequest.ConfirmDenyToReceiver" : "FriendRequest.Interact.Ignored";
        event.replyEmbeds(LanguageManager.getEmbedForUser(resultKey, receiver.getId(), replacements).build())
                .setEphemeral(true).queue(ignored -> event.getMessage().delete().queue(null, error -> {}));
    }

    private static void reply(UserObject receiver, ButtonInteractionEvent event, String key) {
        event.replyEmbeds(LanguageManager.getEmbedForUser(key, receiver.getId(), new HashMap<>()).build())
                .setEphemeral(true).queue();
    }
}
