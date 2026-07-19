package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.lobby.LobbyRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
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

public final class LobbyAdminListener extends ListenerAdapter {
    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().startsWith("lobbyKickMember-") || event.getValues().isEmpty()) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        int lobbyId = integer(event.getComponentId().substring("lobbyKickMember-".length()));
        int targetId = integer(event.getValues().getFirst());
        if (lobbyId <= 0 || targetId <= 0) return;
        event.replyModal(Modal.create("lobbyKickSubmit-" + lobbyId + "-" + targetId,
                        t(user, "Lobby.Kick.Modal.Title"))
                .addComponents(Label.of(t(user, "Lobby.Kick.Modal.Reason"),
                        TextInput.create("reason", TextInputStyle.PARAGRAPH).setRequired(false)
                                .setMaxLength(1000).build())).build()).queue();
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (!event.getComponentId().startsWith("lobbyLeave-")) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        int lobbyId = integer(event.getComponentId().substring("lobbyLeave-".length()));
        boolean left = Main.lobbyService.leaveLobby(lobbyId, user.getId());
        event.reply(t(user, left ? "Lobby.Leave.Success" : "Lobby.Leave.Failed")).setEphemeral(true).queue();
        if (left) new UserControlManager(event.getMessage(), user).loadStartPage();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!event.getModalId().startsWith("lobbyKickSubmit-")) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        String[] parts = event.getModalId().split("-");
        if (parts.length != 3) return;
        int lobbyId = integer(parts[1]), targetId = integer(parts[2]);
        String reason = event.getValue("reason") == null ? "" : event.getValue("reason").getAsString().trim();
        boolean kicked = Main.lobbyService.kickMember(lobbyId, user.getId(), targetId, reason);
        event.reply(t(user, kicked ? "Lobby.Kick.Success" : "Lobby.Kick.Failed")).setEphemeral(true).queue();
        if (kicked) new UserControlManager(event.getMessage(), user).loadLobbyPage(LobbyRepository.get(lobbyId));
    }

    private static int integer(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException e) { return 0; } }
    private static String t(UserObject user, String key) { return LanguageManager.getMessageForUser(key, user.getId()); }
}
