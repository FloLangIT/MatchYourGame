package de.flolang.matchyourgame.manager;

import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Message;

import java.util.HashMap;

public class UserControlManager {

    private Message message;
    private UserObject userObject;

    public UserControlManager(Message message, UserObject userObject) {
        this.message = message;
        this.userObject = userObject;
    }

    public void loadStartPage() {
        if (!message.isPinned()) {
            message.pin().queue(unused -> {
                message.getChannel().getHistory().retrievePast(1).queue(messages -> {
                    messages.forEach(mess -> {
                        if (!mess.isPinned() && mess.getAuthor().isBot())
                            mess.delete().queue();
                    });
                });
            });
        }
        HashMap<String, String> replacings = new HashMap<>();
        replacings.put("%userId%", String.valueOf(userObject.getId()));
        replacings.put("%username%", userObject.getUsername());
        replacings.put("%createdAt%", "<t:" + userObject.getCreatedAt().getTime() / 1000 + ":R>");
        message.editMessageEmbeds(LanguageManager.getEmbedForUser("UserProfile", userObject.getId(), replacings).build()).setComponents(
                ActionRow.of(Button.primary("editProfile", LanguageManager.getMessageForUser("UserProfile.Button.EditProfile", userObject.getId()))),
                ActionRow.of(Button.secondary("passiveQ", LanguageManager.getMessageForUser("UserProfile.Button.PassiveQ", userObject.getId())),
                        Button.success("createLobby", LanguageManager.getMessageForUser("UserProfile.Button.CreateLobby", userObject.getId()))),
                ActionRow.of(Button.success("friends", LanguageManager.getMessageForUser("UserProfile.Button.Friends", userObject.getId())),
                        Button.success("crews", LanguageManager.getMessageForUser("UserProfile.Button.Crews", userObject.getId())))
        ).queue();
    }

    public void loadFriendsMainPage() {
        HashMap<String, String> replacings = new HashMap<>();
        message.editMessageEmbeds(LanguageManager.getEmbedForUser("UserProfile.Friends", userObject.getId(), replacings).build()).setComponents(
                ActionRow.of(Button.primary("mainPage", LanguageManager.getMessageForUser("UserProfile.Button.Back", userObject.getId()))),
                ActionRow.of(Button.success("addFriend", LanguageManager.getMessageForUser("UserProfile.Friends.Button.AddFriend", userObject.getId())))
        ).queue();
    }

}
