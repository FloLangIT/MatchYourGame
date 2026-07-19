package de.flolang.matchyourgame.manager;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.friend.FriendRepository;
import de.flolang.matchyourgame.database.party.PartyRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.components.ActionComponent;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

public final class ManagementMessageUpdater {
    private ManagementMessageUpdater() {}

    public static void refreshPartyState(Collection<Integer> affectedUsers) {
        Set<Integer> users = new HashSet<>(affectedUsers);
        for (int userId : users) refreshOpenManagementPage(userId, true);
        refreshFriendActivity(users);
    }

    public static void refreshFriendActivity(int activityUserId) {
        refreshFriendActivity(Set.of(activityUserId));
    }

    public static void refreshFriendActivity(Collection<Integer> activityUsers) {
        Set<Integer> friends = new HashSet<>();
        for (int userId : activityUsers) friends.addAll(FriendRepository.getAcceptedFriendIds(userId));
        for (int friendId : friends) refreshOpenManagementPage(friendId, false);
    }

    public static void refreshMainPage(int userId) {
        refreshOpenManagementPage(userId, false);
    }

    private static void refreshOpenManagementPage(int userId, boolean includePartyPage) {
        if (Main.jda == null) return;
        UserObject user = UserController.get(userId);
        if (user == null) return;
        Main.jda.retrieveUserById(user.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
                dm.retrievePinnedMessages().limit(50).queue(pins -> pins.stream()
                        .filter(pin -> pin.getMessage().getAuthor().getIdLong() == Main.jda.getSelfUser().getIdLong())
                        .max(Comparator.comparing(pin -> pin.getTimePinned())).map(pin -> pin.getMessage())
                        .ifPresent(message -> refreshIfMatching(message, user, includePartyPage)))));
    }

    private static void refreshIfMatching(Message message, UserObject user, boolean includePartyPage) {
        Set<String> ids = new HashSet<>();
        message.getComponentTree().findAll(ActionComponent.class)
                .forEach(component -> ids.add(component.getCustomId()));
        UserControlManager manager = new UserControlManager(message, user);
        if (ids.contains("editProfile")) {
            manager.loadStartPage();
        } else if (includePartyPage && ids.contains("partyLeave")) {
            if (PartyRepository.getForUser(user.getId()) == null) manager.loadStartPage();
            else manager.loadPartyPage(currentPartyPage(ids));
        }
    }

    private static int currentPartyPage(Set<String> componentIds) {
        return componentIds.stream().filter(id -> id != null && id.startsWith("partyFriendSelect-"))
                .map(id -> id.substring("partyFriendSelect-".length()))
                .mapToInt(value -> {
                    try { return Integer.parseInt(value); }
                    catch (NumberFormatException ignored) { return 0; }
                }).max().orElse(0);
    }
}
