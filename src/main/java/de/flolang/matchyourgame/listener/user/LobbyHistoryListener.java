package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.block.BlockRepository;
import de.flolang.matchyourgame.database.history.LobbyHistoryRepository;
import de.flolang.matchyourgame.database.lobby.LobbyRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.logging.DiscordLogService;
import de.flolang.matchyourgame.manager.FriendRequestManager;
import de.flolang.matchyourgame.manager.UserControlManager;
import de.flolang.matchyourgame.manager.history.LobbyHistoryManager;
import de.flolang.matchyourgame.manager.lobby.LobbyService;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

import java.util.Map;

public final class LobbyHistoryListener extends ListenerAdapter {
    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null || id == null) return;
        LobbyHistoryManager manager = new LobbyHistoryManager(event.getMessage(), user);
        try {
            if (id.equals("history")) {
                event.deferEdit().queue();
                manager.loadGames(0);
            } else if (id.startsWith("historyGamesPage-")) {
                event.deferEdit().queue();
                manager.loadGames(integer(id.substring("historyGamesPage-".length())));
            } else if (id.startsWith("historyLobbiesPage-")) {
                int[] parts = parts(id, 4);
                event.deferEdit().queue();
                manager.loadLobbies(parts[0], nullable(parts[1]), parts[2], parts[3]);
            } else if (id.startsWith("historyLobbyBack-")) {
                int[] parts = parts(id, 5);
                event.deferEdit().queue();
                manager.loadLobby(parts[0], parts[1], nullable(parts[2]), parts[3], parts[4]);
            } else if (id.startsWith("historyMembersPage-")) {
                int[] parts = parts(id, 6);
                event.deferEdit().queue();
                manager.loadLobby(parts[0], parts[1], nullable(parts[2]), parts[3], parts[4], parts[5]);
            } else if (id.startsWith("historyMatchesPage-")) {
                int[] parts = parts(id, 7);
                event.deferEdit().queue();
                manager.loadLobby(parts[0], parts[1], nullable(parts[2]), parts[3], parts[4],
                        parts[5], parts[6]);
            } else if (id.startsWith("lobbyMemberActions-")) {
                int[] parts = parts(id, 2);
                event.deferEdit().queue();
                manager.loadCurrentMembers(parts[0], parts[1]);
            } else if (id.startsWith("historyCurrentLobbyBack-")) {
                int lobbyId = integer(id.substring("historyCurrentLobbyBack-".length()));
                event.deferEdit().queue();
                new UserControlManager(event.getMessage(), user)
                        .loadLobbyPage(LobbyRepository.get(lobbyId));
            } else if (id.startsWith("historyBlockAsk-")) {
                int[] parts = parts(id, 7);
                event.deferEdit().queue();
                manager.loadBlockConfirmation(parts[0], parts[1], parts[2] == 1,
                        parts[3], nullable(parts[4]), parts[5], parts[6]);
            } else if (id.startsWith("historyBlockCancel-")) {
                int[] parts = parts(id, 7);
                event.deferEdit().queue();
                manager.loadMember(parts[0], parts[1], parts[2] == 1,
                        parts[3], nullable(parts[4]), parts[5], parts[6]);
            } else if (id.startsWith("historyBlockConfirm-")) {
                int[] parts = parts(id, 7);
                if (!LobbyHistoryRepository.sharedLobby(user.getId(), parts[1], parts[0])) {
                    event.reply(t(user, "History.Action.Unavailable")).setEphemeral(true).queue();
                    return;
                }
                BlockRepository.BlockResult result = BlockRepository.block(user.getId(), parts[1]);
                UserObject target = UserController.get(parts[1]);
                if (result != BlockRepository.BlockResult.FAILED)
                    DiscordLogService.action("USER_BLOCK", "User " + user.getUsername() + " (#" + user.getId()
                            + ") hat " + (target == null ? "#" + parts[1] : target.getUsername())
                            + " über Lobby #" + parts[0] + " blockiert (" + result + ")");
                event.reply(t(user, "History.Block.Result." + result.name())).setEphemeral(true).queue(ignored ->
                        manager.loadMember(parts[0], parts[1], parts[2] == 1,
                                parts[3], nullable(parts[4]), parts[5], parts[6]));
            } else if (id.startsWith("historyFriend-")) {
                int[] parts = simplePair(id);
                if (!LobbyHistoryRepository.sharedLobby(user.getId(), parts[1], parts[0])) {
                    event.reply(t(user, "History.Action.Unavailable")).setEphemeral(true).queue();
                    return;
                }
                FriendRequestManager.RequestResult result =
                        FriendRequestManager.request(user.getId(), parts[1]);
                event.reply(t(user, "History.Friend.Result." + result.name())).setEphemeral(true).queue();
            } else if (id.startsWith("historyInvite-")) {
                int[] parts = simplePair(id);
                LobbyService.FriendInviteResult result =
                        Main.lobbyService.inviteHistoryMember(user.getId(), parts[0], parts[1]);
                event.reply(t(user, "History.Invite.Result." + result.status().name()))
                        .setEphemeral(true).queue();
            } else if (id.startsWith("historyKick-")) {
                int[] parts = simplePair(id);
                event.replyModal(Modal.create("historyKickSubmit-" + parts[0] + "-" + parts[1],
                                t(user, "Lobby.Kick.Modal.Title"))
                        .addComponents(Label.of(t(user, "Lobby.Kick.Modal.Reason"),
                                TextInput.create("reason", TextInputStyle.PARAGRAPH).setRequired(false)
                                        .setMaxLength(1000).build())).build()).queue();
            }
        } catch (IllegalArgumentException ignored) {
            event.reply(t(user, "History.Action.Unavailable")).setEphemeral(true).queue();
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (event.getValues().isEmpty()) return;
        String id = event.getComponentId();
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null || id == null) return;
        LobbyHistoryManager manager = new LobbyHistoryManager(event.getMessage(), user);
        try {
            if (id.equals("historyGameSelect")) {
                event.deferEdit().queue();
                manager.loadLobbies(integer(event.getValues().getFirst()), null, 0, 0);
            } else if (id.startsWith("historyLobbySelect-")) {
                int[] parts = parts(id, 4);
                event.deferEdit().queue();
                manager.loadLobby(integer(event.getValues().getFirst()), parts[0], nullable(parts[1]),
                        parts[2], parts[3]);
            } else if (id.startsWith("historyTeammateSelect-")) {
                int[] parts = parts(id, 3);
                event.deferEdit().queue();
                manager.loadLobbies(parts[0], nullable(integer(event.getValues().getFirst())), 0, parts[2]);
            } else if (id.startsWith("historyMemberSelect-")) {
                int[] parts = parts(id, 6);
                event.deferEdit().queue();
                manager.loadMember(parts[0], integer(event.getValues().getFirst()), false,
                        parts[1], nullable(parts[2]), parts[3], parts[4]);
            } else if (id.startsWith("historyMatchSelect-")) {
                int[] parts = parts(id, 7);
                event.deferEdit().queue();
                manager.loadMatch(parts[0], integer(event.getValues().getFirst()), parts[1],
                        nullable(parts[2]), parts[3], parts[4], parts[5], parts[6]);
            } else if (id.startsWith("lobbyMemberActionSelect-")) {
                int lobbyId = integer(id.substring("lobbyMemberActionSelect-".length()));
                event.deferEdit().queue();
                manager.loadMember(lobbyId, integer(event.getValues().getFirst()), true,
                        0, null, 0, 0);
            }
        } catch (IllegalArgumentException ignored) {
            event.reply(t(user, "History.Action.Unavailable")).setEphemeral(true).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!event.getModalId().startsWith("historyKickSubmit-")) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        int[] parts;
        try {
            parts = simplePair(event.getModalId());
        } catch (IllegalArgumentException exception) {
            return;
        }
        String reason = event.getValue("reason") == null ? ""
                : event.getValue("reason").getAsString().trim();
        boolean kicked = Main.lobbyService.kickMember(parts[0], user.getId(), parts[1], reason);
        event.reply(t(user, kicked ? "Lobby.Kick.Success" : "Lobby.Kick.Failed"))
                .setEphemeral(true).queue();
        if (kicked) new UserControlManager(event.getMessage(), user)
                .loadLobbyPage(LobbyRepository.get(parts[0]));
    }

    private static int[] parts(String id, int count) {
        String[] values = id.split("-");
        if (values.length != count + 1) throw new IllegalArgumentException();
        int[] result = new int[count];
        for (int i = 0; i < count; i++) result[i] = integer(values[i + 1]);
        return result;
    }

    private static int[] simplePair(String id) {
        return parts(id, 2);
    }

    private static int integer(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private static Integer nullable(int value) {
        return value == 0 ? null : value;
    }

    private static String t(UserObject user, String key) {
        return LanguageManager.getMessageForUser(key, user.getId());
    }

    @SuppressWarnings("unused")
    private static String t(UserObject user, String key, Map<String, String> replacements) {
        return LanguageManager.getMessageForUser(key, user.getId(), replacements);
    }
}
