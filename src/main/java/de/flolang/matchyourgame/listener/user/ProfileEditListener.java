package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.profile.CommunicationLanguageRepository;
import de.flolang.matchyourgame.database.profile.GameProfile;
import de.flolang.matchyourgame.database.profile.GameProfileRepository;
import de.flolang.matchyourgame.database.game.GameRepository;
import de.flolang.matchyourgame.database.gameapi.GameApiRepository;
import de.flolang.matchyourgame.gameapi.GameApiRegistry;
import de.flolang.matchyourgame.database.user.FriendRequestPolicy;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.language.Language;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.language.CommunicationLanguageNames;
import de.flolang.matchyourgame.manager.UserControlManager;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

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
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user)
                    .loadCommunicationLanguagePicker(true, 0);
        } else if (id.startsWith("communicationLanguagePickerPage-")) {
            String[] parts = id.split("-");
            if (parts.length != 3) return;
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadCommunicationLanguagePicker(
                    "1".equals(parts[1]), integer(parts[2]));
        } else if (id.startsWith("communicationLanguagesPage-")) {
            String[] parts = id.split("-");
            if (parts.length != 3) return;
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadCommunicationLanguagesPage(
                    "1".equals(parts[1]), integer(parts[2]));
        } else if (id.equals("gameApiLink")) {
            var profiles = GameProfileRepository.getForUser(user.getId()).stream().filter(profile -> {
                var provider = GameApiRegistry.get(GameApiRepository.providerId(profile.gameId()));
                return provider != null && (provider.accountLoginConfigured()
                        || provider.supportsManualAccountLink());
            }).limit(25).toList();
            if (profiles.isEmpty()) {
                event.reply(t(user, "GameProfile.API.NoLoginProfiles")).setEphemeral(true).queue(); return;
            }
            event.reply(t(user, "GameProfile.API.SelectGame")).setEphemeral(true)
                    .setComponents(ActionRow.of(StringSelectMenu.create("gameApiLoginProfile")
                            .setPlaceholder(t(user, "GameProfile.API.GameDropdown"))
                            .addOptions(profiles.stream().map(profile -> {
                                var game = GameRepository.get(profile.gameId());
                                String name = game == null ? String.valueOf(profile.gameId()) : game.getName();
                                return SelectOption.of(name + " · " + profile.platform(),
                                        profile.gameId() + "|" + profile.platform());
                            }).toList()).build())).queue();
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
        } else if (event.getComponentId().startsWith("profileCommunicationLanguageRemove-")) {
            int page = integer(event.getComponentId().substring(
                    "profileCommunicationLanguageRemove-".length()));
            boolean deleted = CommunicationLanguageRepository.delete(
                    user.getId(), event.getValues().getFirst());
            if (!deleted) {
                event.reply(t(user, "GameProfile.Languages.RemoveFailed"))
                        .setEphemeral(true).queue();
                return;
            }
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadCommunicationLanguagesPage(true, page);
        } else if (event.getComponentId().startsWith("communicationLanguagePick-")) {
            String[] parts = event.getComponentId().split("-");
            if (parts.length != 3 || event.getValues().isEmpty()) return;
            String code = event.getValues().getFirst();
            event.replyModal(Modal.create("communicationLanguageAddSelected-" + parts[1] + "-" + code,
                            t(user, "GameProfile.Languages.Modal.Title"))
                    .addComponents(input(t(user, "GameProfile.Languages.Modal.Priority"),
                            "priority", "1")).build()).queue();
        } else if (event.getComponentId().equals("gameApiLoginProfile")) {
            String[] selected = event.getValues().getFirst().split("\\|", 2);
            if (selected.length != 2) return;
            try {
                int gameId = Integer.parseInt(selected[0]);
                var provider = GameApiRegistry.get(GameApiRepository.providerId(gameId));
                if (provider == null) throw new de.flolang.matchyourgame.gameapi.GameApiException(
                        "Für dieses Game ist keine API aktiviert.");
                if (provider.supportsAccountLogin() && provider.accountLoginConfigured()) {
                    var login = Main.gameApiService.beginAccountLogin(
                            user.getId(), gameId, selected[1]);
                    event.reply(t(user, "GameProfile.API.LoginReady", Map.of(
                                    "%provider%", login.providerName()))).setEphemeral(true)
                            .setComponents(ActionRow.of(net.dv8tion.jda.api.components.buttons.Button.link(
                                    login.authorizationUrl(), t(user, "GameProfile.API.LoginButton")))).queue();
                } else if (provider.supportsManualAccountLink()) {
                    String platform = Base64.getUrlEncoder().withoutPadding().encodeToString(
                            selected[1].getBytes(StandardCharsets.UTF_8));
                    event.replyModal(Modal.create("gameApiManualLink:" + gameId + ":" + platform,
                                    t(user, "GameProfile.API.Title"))
                            .addComponents(Label.of(t(user, "GameProfile.API.Account"),
                                    t(user, "GameProfile.API.AccountDescription"),
                                    TextInput.create("account", TextInputStyle.SHORT)
                                            .setPlaceholder("Name#Tag").setRequired(true)
                                            .setMinLength(3).setMaxLength(40).build())).build()).queue();
                } else {
                    throw new de.flolang.matchyourgame.gameapi.GameApiException(
                            "Die Account-Verknüpfung ist erst nach der Riot-RSO-Freischaltung verfügbar.");
                }
            } catch (NumberFormatException | de.flolang.matchyourgame.gameapi.GameApiException exception) {
                event.reply(t(user, "GameProfile.API.Error", Map.of("%error%", exception.getMessage())))
                        .setEphemeral(true).queue();
            }
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
        } else if (event.getModalId().startsWith("communicationLanguageAddSelected-")) {
            try {
                String[] parts = event.getModalId().split("-");
                if (parts.length != 3) throw new IllegalArgumentException();
                boolean saved = CommunicationLanguageRepository.upsert(user.getId(),
                        parts[2],
                        Integer.parseInt(event.getValue("priority").getAsString()));
                if (!saved) throw new IllegalArgumentException();
                event.deferEdit().queue();
                new UserControlManager(event.getMessage(), user)
                        .loadCommunicationLanguagesPage("1".equals(parts[1]), 0);
            } catch (IllegalArgumentException exception) {
                event.reply(t(user, "UserProfile.Edit.InvalidLanguage")).setEphemeral(true).queue();
            }
        } else if (event.getModalId().startsWith("gameApiManualLink:")) {
            try {
                String[] parts = event.getModalId().split(":", 3);
                int gameId = Integer.parseInt(parts[1]);
                String platform = new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8);
                GameProfile profile = GameProfileRepository.get(user.getId(), gameId, platform);
                if (profile == null) throw new de.flolang.matchyourgame.gameapi.GameApiException(
                        "Dieses Spielprofil existiert nicht.");
                var result = Main.gameApiService.linkProfile(user.getId(), gameId, platform,
                        event.getValue("account").getAsString(), profile.region(), profile.platform());
                String messageKey = result.rankSyncError() == null
                        ? "GameProfile.API.FallbackLinked" : "GameProfile.API.FallbackLinkedWithoutGameData";
                event.reply(t(user, messageKey, Map.of(
                                "%account%", result.accountName(),
                                "%provider%", result.providerName(),
                                "%rank%", result.rankValue() == null ? "-" : String.valueOf(result.rankValue()),
                                "%error%", result.rankSyncError() == null ? "" : result.rankSyncError())))
                        .setEphemeral(true).queue();
            } catch (IllegalArgumentException | de.flolang.matchyourgame.gameapi.GameApiException exception) {
                event.reply(t(user, "GameProfile.API.Error", Map.of("%error%", exception.getMessage())))
                        .setEphemeral(true).queue();
            }
        }
    }

    private static Label input(String label, String id, String placeholder) {
        return Label.of(label, TextInput.create(id, TextInputStyle.SHORT).setPlaceholder(placeholder)
                .setRequired(true).setMaxLength(20).build());
    }

    private static int integer(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException exception) { return 0; }
    }

    private static String t(UserObject user, String key) {
        return LanguageManager.getMessageForUser(key, user.getId());
    }

    private static String t(UserObject user, String key, Map<String, String> replacements) {
        return LanguageManager.getMessageForUser(key, user.getId(), replacements);
    }
}
