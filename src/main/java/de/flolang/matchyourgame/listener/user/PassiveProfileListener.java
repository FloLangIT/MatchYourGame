package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.database.lobby.SearchProfile;
import de.flolang.matchyourgame.database.lobby.SearchProfileRepository;
import de.flolang.matchyourgame.database.game.GameObject;
import de.flolang.matchyourgame.database.game.GameRepository;
import de.flolang.matchyourgame.database.profile.GameProfile;
import de.flolang.matchyourgame.database.profile.GameProfileRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.manager.UserControlManager;
import de.flolang.matchyourgame.manager.ManagementMessageUpdater;
import de.flolang.matchyourgame.language.LanguageManager;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public final class PassiveProfileListener extends ListenerAdapter {
    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().startsWith("passiveProfileToggle-")) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        int page = Integer.parseInt(event.getComponentId().substring("passiveProfileToggle-".length()));
        String[] value = event.getValues().getFirst().split("\\|", 2);
        int gameId = Integer.parseInt(value[0]);
        String platform = value[1];
        GameProfile profile = GameProfileRepository.get(user.getId(), gameId, platform);
        if (profile == null) {
            event.reply(LanguageManager.getMessageForUser("GameProfile.Manage.ProfileUnavailable", user.getId()))
                    .setEphemeral(true).queue();
            new UserControlManager(event.getMessage(), user).loadPassivePage(page);
            return;
        }
        SearchProfile existing = SearchProfileRepository.get(user.getId(), gameId, platform);
        boolean enabled = existing == null || !existing.passiveEnabled();
        SearchProfileRepository.upsert(user.getId(), gameId, platform, profile.region(), user.getLanguage().name(),
                profile.rankValue(), profile.preferredRole(), enabled);
        ManagementMessageUpdater.refreshFriendActivity(user.getId());
        event.reply(LanguageManager.getMessageForUser(enabled
                        ? "PassiveQ.Profile.Activated" : "PassiveQ.Profile.Deactivated", user.getId(),
                java.util.Map.of("%game%", gameDisplayName(gameId), "%platform%", platform)))
                .setEphemeral(true).queue();
        new UserControlManager(event.getMessage(), user).loadPassivePage(page);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (!event.getComponentId().startsWith("passivePage-")) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        int page = Integer.parseInt(event.getComponentId().substring("passivePage-".length()));
        event.deferEdit().queue();
        new UserControlManager(event.getMessage(), user).loadPassivePage(page);
    }

    private static String gameDisplayName(int gameId) {
        GameObject game = GameRepository.get(gameId);
        if (game == null) return "#" + gameId;
        GameObject parent = game.getSubGameFrom();
        return parent == null ? game.getName() : parent.getName() + " · " + game.getName();
    }
}
