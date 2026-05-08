package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.UserControlManager;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

public class UserProfileButtonListener extends ListenerAdapter {

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        //Main Page
        if(event.getButton().getCustomId().startsWith("friends")) {
            UserObject userObject = UserController.get(event.getUser().getIdLong());
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), userObject).loadFriendsMainPage();
        }

        //general
        else if(event.getButton().getCustomId().startsWith("mainPage")) {
            UserObject userObject = UserController.get(event.getUser().getIdLong());
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), userObject).loadStartPage();
        }

        //Friends
        else if(event.getButton().getCustomId().startsWith("addFriend")) {
            UserObject userObject = UserController.get(event.getUser().getIdLong());
            Modal modal = Modal.create("addFriend", LanguageManager.getMessageForUser("UserProfile.Friends.AddFriend.Modal.Title", userObject.getId()))
                    .addComponents(Label.of(
                            LanguageManager.getMessageForUser("UserProfile.Friends.AddFriend.Modal.Username", userObject.getId()),
                            TextInput.create("username", TextInputStyle.SHORT).setRequired(true).build()
                    )).build();
            event.replyModal(modal).queue();
        }
    }
}
