package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.language.LanguageManager;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

public final class VoiceControlListener extends ListenerAdapter {
    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (!event.getComponentId().startsWith("voiceExtend-")) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        int lobbyId = Integer.parseInt(event.getComponentId().substring("voiceExtend-".length()));
        boolean extended = Main.lobbyService.extendVoiceDeadline(lobbyId, user.getId());
        event.editMessage(LanguageManager.getMessageForUser(extended
                ? "Lobby.Voice.Rejoin.Extended" : "Lobby.Voice.Rejoin.ExtensionUnavailable", user.getId()))
                .setComponents(ActionRow.of(Button.danger("delete",
                        LanguageManager.getMessageForUser("General.Button.DeleteMessage", user.getId())))).queue();
    }
}
