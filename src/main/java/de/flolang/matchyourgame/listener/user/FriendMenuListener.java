package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.database.friend.FriendRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.manager.UserControlManager;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public final class FriendMenuListener extends ListenerAdapter {
    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().startsWith("friendSelect-")) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        int page = Integer.parseInt(event.getComponentId().substring("friendSelect-".length()));
        int friendId = Integer.parseInt(event.getValues().getFirst());
        event.deferEdit().queue();
        new UserControlManager(event.getMessage(), user).loadFriendProfile(friendId, page);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        if (id.startsWith("friendsPage-")) {
            int page = Integer.parseInt(id.substring("friendsPage-".length()));
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadFriendsMainPage(page);
        } else if (id.startsWith("friendRemove-")) {
            String[] parts = id.split("-");
            int friendId = Integer.parseInt(parts[1]);
            int page = Integer.parseInt(parts[2]);
            FriendRepository.delete(user.getId(), friendId);
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadFriendsMainPage(page);
        }
    }
}
