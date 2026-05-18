package de.flolang.matchyourgame.manager;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.friend.FriendObject;
import de.flolang.matchyourgame.database.friend.FriendRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.language.LanguageManager;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.TimeZone;

public class FriendRequestManager {

    private FriendObject friendObject;

    public FriendRequestManager(FriendObject friendObject) {
        this.friendObject = friendObject;
    }

    public void acceptFriendRequest() {
        Main.jda.getUserById(UserController.get(friendObject.getRequesterID()).getDiscordID()).openPrivateChannel().queue(privateChannel -> {
            HashMap<String, String> replacing = new HashMap<>();
            replacing.put("%receiver%", UserController.get(friendObject.getReceiverID()).getUsername());
            replacing.put("%timestamp%", "<t:" + friendObject.getSent_at().getTime() / 1000 + ":R>");
            privateChannel.sendMessageEmbeds(LanguageManager.getEmbedForUser("FriendRequest.AcceptRequest", friendObject.getRequesterID(), replacing).build()).addComponents(ActionRow.of(Button.danger("delete", LanguageManager.getMessageForUser("General.Button.DeleteMessage", friendObject.getRequesterID())))).queue();
        });
        friendObject.setAccepted_at(Timestamp.from(Instant.now()));
        FriendRepository.update(friendObject);
    }

    public static void sendFriendRequest(int requesterID, int receiverID) {
        Main.jda.getUserById(UserController.get(receiverID).getDiscordID()).openPrivateChannel().queue(privateChannel -> {
            HashMap<String, String> replacing = new HashMap<>();
            replacing.put("%requester%", UserController.get(requesterID).getUsername());
            privateChannel.sendMessageEmbeds(LanguageManager.getEmbedForUser("FriendRequest.RequestReceive", receiverID, replacing).build()).setComponents(
                    ActionRow.of(
                            Button.success("friendAccept-" + requesterID, LanguageManager.getMessageForUser("FriendRequest.RequestReceive.Button.Accept", requesterID)),
                            Button.success("friendDeny-" + requesterID, LanguageManager.getMessageForUser("FriendRequest.RequestReceive.Button.Deny", requesterID)),
                            Button.success("delete", LanguageManager.getMessageForUser("FriendRequest.RequestReceive.Button.Ignore", requesterID))
                            )
            ).queue();
        });
        FriendRepository.create(requesterID, receiverID);
    }

}
