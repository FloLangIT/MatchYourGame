package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.database.friend.FriendObject;
import de.flolang.matchyourgame.database.friend.FriendRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.FriendRequestManager;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.HashMap;

public class FriendRequestHandleListener extends ListenerAdapter {

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if(event.getButton().getCustomId().startsWith("friendAccept-")) {
            String[] args = event.getButton().getCustomId().split("-");
            int requesterID = Integer.parseInt(args[1]);
            UserObject userObject = UserController.get(event.getIdLong());
            FriendObject friendObject = FriendRepository.get(requesterID, userObject.getId());
            if(friendObject == null) {
                event.replyEmbeds(LanguageManager.getEmbedForUser("FriendRequest.Interact.NoFriendRequest", userObject.getId(), new HashMap<>()).build()).setEphemeral(true).queue();
                return;
            }
            if(friendObject.getAccepted_at() != null) {
                event.replyEmbeds(LanguageManager.getEmbedForUser("FriendRequest.Interact.AlreadyAccepted", userObject.getId(), new HashMap<>()).build()).setEphemeral(true).queue();
                return;
            }
            if(friendObject.getReceiverID() != userObject.getId()) {
                event.replyEmbeds(LanguageManager.getEmbedForUser("FriendRequest.Interact.NoFriendRequest", userObject.getId(), new HashMap<>()).build()).setEphemeral(true).queue();
                return;
            }
            new FriendRequestManager(friendObject).acceptFriendRequest();
            HashMap<String, String> replacings = new HashMap<>();
            replacings.put("%requester%", UserController.get(requesterID).getUsername());
            event.replyEmbeds(LanguageManager.getEmbedForUser("FriendRequest.ConfirmAcceptToReceiver", userObject.getId(), replacings).build()).setEphemeral(true).queue();
        } else if(event.getButton().getCustomId().startsWith("friendDeny-")) {
            String[] args = event.getButton().getCustomId().split("-");
            int requesterID = Integer.parseInt(args[1]);
            UserObject userObject = UserController.get(event.getIdLong());
            FriendObject friendObject = FriendRepository.get(requesterID, userObject.getId());
            if(friendObject == null) {
                event.replyEmbeds(LanguageManager.getEmbedForUser("FriendRequest.Interact.NoFriendRequest", userObject.getId(), new HashMap<>()).build()).setEphemeral(true).queue();
                return;
            }
            if(friendObject.getAccepted_at() != null) {
                event.replyEmbeds(LanguageManager.getEmbedForUser("FriendRequest.Interact.AlreadyAccepted", userObject.getId(), new HashMap<>()).build()).setEphemeral(true).queue();
                return;
            }
            if(friendObject.getReceiverID() != userObject.getId()) {
                event.replyEmbeds(LanguageManager.getEmbedForUser("FriendRequest.Interact.NoFriendRequest", userObject.getId(), new HashMap<>()).build()).setEphemeral(true).queue();
                return;
            }
            new FriendRequestManager(friendObject).denyFriendRequest();
            HashMap<String, String> replacings = new HashMap<>();
            replacings.put("%requester%", UserController.get(requesterID).getUsername());
            event.replyEmbeds(LanguageManager.getEmbedForUser("FriendRequest.ConfirmDenyToReceiver", userObject.getId(), replacings).build()).setEphemeral(true).queue();
        }
    }
}
