package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.database.friend.FriendObject;
import de.flolang.matchyourgame.database.friend.FriendRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.FriendRequestManager;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.HashMap;

public class AddFriendListener extends ListenerAdapter {

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if(!event.getModalId().equals("addFriend")) {
            return;
        }
        UserObject selfUser = UserController.get(event.getUser().getIdLong());

        String username = event.getValue("username").getAsString();
        UserObject userObject = UserController.get(username);
        if(userObject == null) {
            HashMap<String, String> replacings = new HashMap<>();
            replacings.put("%username%", username);
            event.replyEmbeds(LanguageManager.getEmbedForUser("General.NoUserWithUsername", selfUser.getId(), replacings).build()).setEphemeral(true).queue();
            return;
        }
        username = userObject.getUsername();
        if(userObject.getId() == selfUser.getId()) {
            event.replyEmbeds(LanguageManager.getEmbedForUser("UserProfile.Friends.AddFriend.CantAddSelf", selfUser.getId(), new HashMap<>()).build()).setEphemeral(true).queue();
            return;
        }
        FriendObject friendObject = FriendRepository.get(selfUser.getId(), userObject.getId());
        //Check there are already friends
        if(friendObject != null) {
            //Check if friend request isn't accepted
            if (friendObject.getAccepted_at() == null) {
                //Check friend request sender is now receiver than accept old request.
                if (friendObject.getRequesterID() == selfUser.getId()) {
                    HashMap<String, String> replacings = new HashMap<>();
                    replacings.put("%username%", username);
                    event.replyEmbeds(LanguageManager.getEmbedForUser("UserProfile.Friends.AddFriend.AlreadyAdded", selfUser.getId(), replacings).build()).setEphemeral(true).queue();
                    return;
                } else {
                    //Accept request
                    new FriendRequestManager(friendObject).acceptFriendRequest();
                    HashMap<String, String> replacings = new HashMap<>();
                    replacings.put("%requester%", username);
                    event.replyEmbeds(LanguageManager.getEmbedForUser("FriendRequest.ConfirmAcceptToReceiver", selfUser.getId(), replacings).build()).setEphemeral(true).queue();
                }
            } else {
                HashMap<String, String> replacings = new HashMap<>();
                replacings.put("%username%", username);
                event.replyEmbeds(LanguageManager.getEmbedForUser("UserProfile.Friends.AddFriend.AlreadyFriends", selfUser.getId(), replacings).build()).setEphemeral(true).queue();
            }
            return;
        }
        FriendRequestManager.sendFriendRequest(selfUser.getId(), userObject.getId());
        HashMap<String, String> replacings = new HashMap<>();
        replacings.put("%username%", username);
        event.replyEmbeds(LanguageManager.getEmbedForUser("UserProfile.Friends.AddFriend.RequestSent", selfUser.getId(), replacings).build()).setEphemeral(true).queue();
    }
}
