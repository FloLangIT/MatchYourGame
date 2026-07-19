package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.database.profile.CommunicationLanguageRepository;
import de.flolang.matchyourgame.database.user.FriendRequestPolicy;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.language.Language;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.UserControlManager;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

public final class ProfileEditListener extends ListenerAdapter {
    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        if (id.equals("editProfile")) {
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadEditProfilePage();
        } else if (id.equals("profileUsername")) {
            event.replyModal(Modal.create("profileUsername", t(user, "UserProfile.Edit.UsernameModal.Title"))
                    .addComponents(Label.of(t(user, "UserProfile.Edit.UsernameModal.Username"),
                            TextInput.create("username", TextInputStyle.SHORT).setValue(user.getUsername())
                                    .setRequired(true).setMinLength(1).setMaxLength(30).build())).build()).queue();
        } else if (id.equals("profileCommunicationLanguages")) {
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadCommunicationLanguagesPage(true);
        } else if (id.equals("profileCommunicationLanguageAdd")) {
            event.replyModal(Modal.create("profileCommunicationLanguageAdd",
                            t(user, "GameProfile.Languages.Modal.Title"))
                    .addComponents(input(t(user, "GameProfile.Languages.Modal.Language"), "language", "DE"),
                            input(t(user, "GameProfile.Languages.Modal.Priority"), "priority", "1")).build()).queue();
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        if (event.getComponentId().equals("profileMessageLanguage")) {
            Language language;
            try { language = Language.valueOf(event.getValues().getFirst()); }
            catch (IllegalArgumentException exception) { return; }
            UserController.updateProfile(user, user.getUsername(), language);
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadEditProfilePage();
        } else if (event.getComponentId().equals("profileFriendRequestPolicy")) {
            FriendRequestPolicy policy;
            try { policy = FriendRequestPolicy.valueOf(event.getValues().getFirst()); }
            catch (IllegalArgumentException exception) { return; }
            UserRepository.setFriendRequestPolicy(user.getId(), policy);
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadEditProfilePage();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        if (event.getModalId().equals("profileUsername")) {
            String username = event.getValue("username").getAsString().trim();
            boolean saved = UserController.updateProfile(user, username, user.getLanguage());
            if (!saved) {
                event.reply(t(user, "UserProfile.Edit.UsernameModal.Unavailable")).setEphemeral(true).queue();
                return;
            }
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadEditProfilePage();
        } else if (event.getModalId().equals("profileCommunicationLanguageAdd")) {
            try {
                boolean saved = CommunicationLanguageRepository.upsert(user.getId(),
                        event.getValue("language").getAsString(),
                        Integer.parseInt(event.getValue("priority").getAsString()));
                if (!saved) throw new IllegalArgumentException();
                event.deferEdit().queue();
                new UserControlManager(event.getMessage(), user).loadCommunicationLanguagesPage(true);
            } catch (IllegalArgumentException exception) {
                event.reply(t(user, "UserProfile.Edit.InvalidLanguage")).setEphemeral(true).queue();
            }
        }
    }

    private static Label input(String label, String id, String placeholder) {
        return Label.of(label, TextInput.create(id, TextInputStyle.SHORT).setPlaceholder(placeholder)
                .setRequired(true).setMaxLength(20).build());
    }

    private static String t(UserObject user, String key) {
        return LanguageManager.getMessageForUser(key, user.getId());
    }
}
