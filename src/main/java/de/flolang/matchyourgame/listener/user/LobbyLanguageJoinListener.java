package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.lobby.LobbyJoinResult;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public final class LobbyLanguageJoinListener extends ListenerAdapter {
    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().startsWith("lobbyAddLanguageJoin-")) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        int lobbyId = Integer.parseInt(event.getComponentId().substring("lobbyAddLanguageJoin-".length()));
        LobbyJoinResult result = Main.lobbyService.addLanguageAndJoin(lobbyId, user.getId(), event.getValues().getFirst());
        event.editMessage(LanguageManager.getMessageForUser("Lobby.JoinResult." + result.name(), user.getId()))
                .setComponents().queue();
    }
}
