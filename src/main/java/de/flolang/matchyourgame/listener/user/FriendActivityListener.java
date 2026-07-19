package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.database.lobby.PassiveQueueSettingsRepository;
import de.flolang.matchyourgame.database.lobby.SearchProfileRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.manager.ManagementMessageUpdater;
import net.dv8tion.jda.api.events.user.update.UserUpdateOnlineStatusEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public final class FriendActivityListener extends ListenerAdapter {
    @Override
    public void onUserUpdateOnlineStatus(UserUpdateOnlineStatusEvent event) {
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null || !PassiveQueueSettingsRepository.get(user.getId()).syncOnlineStatus()
                || SearchProfileRepository.getForUser(user.getId()).stream().noneMatch(profile -> profile.passiveEnabled())) return;
        ManagementMessageUpdater.refreshFriendActivity(user.getId());
    }
}
