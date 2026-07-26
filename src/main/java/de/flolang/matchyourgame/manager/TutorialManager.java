package de.flolang.matchyourgame.manager;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.game.GameObject;
import de.flolang.matchyourgame.database.game.GameOption;
import de.flolang.matchyourgame.database.game.GameOptionRepository;
import de.flolang.matchyourgame.database.game.GameRepository;
import de.flolang.matchyourgame.database.profile.GameProfile;
import de.flolang.matchyourgame.database.profile.GameProfileRepository;
import de.flolang.matchyourgame.database.profile.CommunicationLanguageRepository;
import de.flolang.matchyourgame.database.party.PartyRepository;
import de.flolang.matchyourgame.database.lobby.LobbyRepository;
import de.flolang.matchyourgame.database.tutorial.TutorialSessionRepository;
import de.flolang.matchyourgame.database.tutorial.TutorialSessionRepository.Session;
import de.flolang.matchyourgame.database.tutorial.TutorialSessionRepository.Step;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.language.CommunicationLanguageNames;
import de.flolang.matchyourgame.manager.lobby.GameMessageVisibility;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageType;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.modals.Modal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Interactive onboarding. Apart from real game profiles, every entity shown here is simulated.
 */
public final class TutorialManager {
    private static final int TUTORIAL_CAPACITY = 5;

    private TutorialManager() {}

    public static void start(Message message, UserObject user) {
        pinManagementMessage(message);
        Session session = TutorialSessionRepository.reset(user.getId());
        if (session == null) {
            new UserControlManager(message, user).loadStartPage();
            return;
        }
        render(message, user, session);
    }

    public enum RestartResult { STARTED, ACTIVE_PARTY, ACTIVE_LOBBY, MESSAGE_NOT_FOUND }

    public static void restartExisting(UserObject user, Consumer<RestartResult> callback) {
        if (LobbyRepository.getActiveForUser(user.getId()) != null) {
            callback.accept(RestartResult.ACTIVE_LOBBY);
            return;
        }
        if (PartyRepository.getForUser(user.getId()) != null) {
            callback.accept(RestartResult.ACTIVE_PARTY);
            return;
        }
        Main.jda.retrieveUserById(user.getDiscordID()).queue(discordUser ->
                discordUser.openPrivateChannel().queue(dm ->
                        dm.retrievePinnedMessages().limit(50).queue(pins -> pins.stream()
                                .filter(pin -> pin.getMessage().getAuthor().getIdLong()
                                        == Main.jda.getSelfUser().getIdLong())
                                .max(Comparator.comparing(pin -> pin.getTimePinned()))
                                .map(pin -> pin.getMessage()).ifPresentOrElse(message -> {
                                    start(message, user);
                                    callback.accept(RestartResult.STARTED);
                                }, () -> callback.accept(RestartResult.MESSAGE_NOT_FOUND)),
                                error -> callback.accept(RestartResult.MESSAGE_NOT_FOUND)),
                        error -> callback.accept(RestartResult.MESSAGE_NOT_FOUND)),
                error -> callback.accept(RestartResult.MESSAGE_NOT_FOUND));
    }

    public static void resume(Message message, UserObject user) {
        Session session = TutorialSessionRepository.get(user.getId());
        if (session == null) {
            new UserControlManager(message, user).loadStartPage();
            return;
        }
        render(message, user, session);
        if (isPending(session.step()))
            CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS)
                    .execute(() -> completePending(message, user, session));
    }

    public static void handleButton(ButtonInteractionEvent event, UserObject user) {
        String id = event.getComponentId();
        Session session = TutorialSessionRepository.get(user.getId());
        if (id.equals("tutorialExit")) {
            TutorialSessionRepository.delete(user.getId());
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadStartPage();
            return;
        }
        if (session == null) {
            event.reply(t(user, "Tutorial.Setup.Expired")).setEphemeral(true).queue();
            return;
        }
        if (id.equals("tutorialExploreParty")) {
            event.reply(t(user, "Tutorial.Setup.Party.Explore")).setEphemeral(true).queue();
            return;
        }
        if (id.equals("tutorialConfigureProfile")) {
            if (session.step() != Step.PROFILE_SETUP || session.profileIndex() >= session.modeIds().size()) return;
            Main.gameSelectionWizard.startTutorialProfile(event, user,
                    session.modeIds().get(session.profileIndex()));
            return;
        }
        if (id.equals("tutorialInvitePartyUser")) {
            beginPartyInvitation(event, user, session);
            return;
        }
        if (id.equals("tutorialPartyLeave")) {
            if (session.partySize() == 0) return;
            Session next = session.withPartySize(0).withStep(Step.LOBBY_SELECTION);
            TutorialSessionRepository.save(next);
            event.deferEdit().queue();
            render(event.getMessage(), user, next);
            return;
        }
        if (id.equals("tutorialInviteFriend")) {
            if (session.step() != Step.LOBBY_ACTIVE || session.partySize() == 2) return;
            event.replyModal(Modal.create("tutorialInviteFriend",
                            t(user, "Lobby.InviteFriend.Modal.Title"))
                    .addComponents(Label.of(t(user, "Lobby.InviteFriend.Modal.Username"),
                            TextInput.create("username", TextInputStyle.SHORT)
                                    .setValue("Tutorial123").setRequired(true).build())).build()).queue();
            return;
        }
        if (id.equals("tutorialInviteClan")) {
            showClanSelection(event, user, session);
            return;
        }
        if (id.equals("tutorialEnablePassive")) {
            beginPassiveInvitations(event, user, session);
            return;
        }
        if (id.equals("tutorialFinalPassiveAccept")) {
            Session next = session.withStep(Step.FINAL_LOBBY_MEMBER);
            TutorialSessionRepository.save(next);
            event.reply(t(user, "Lobby.JoinResult.JOINED")).setEphemeral(true).queue();
            render(event.getMessage(), user, next);
            return;
        }
        if (id.equals("tutorialFinalPassiveDecline")) {
            completeTutorial(event, user, "Lobby.InvitationDeclined");
            return;
        }
        if (id.equals("tutorialFinalLobbyLeave")) {
            completeTutorial(event, user, "Lobby.Leave.Success");
            return;
        }
        Session next = switch (id) {
            case "tutorialBegin" -> session.withStep(Step.GAME_SELECTION);
            case "tutorialCreateParty" -> session.withPartySize(1).withStep(Step.PARTY_CREATED);
            case "tutorialPartyContinue" -> session.withStep(Step.LOBBY_SELECTION);
            case "tutorialCloseLobby" -> session.withStep(Step.MATCH_ENTRY);
            case "tutorialMatchAdd" -> session.withStep(Step.MATCH_PARTICIPANTS);
            case "tutorialMatchSkip" -> session.withStep(Step.PASSIVE_EXAMPLE);
            case "tutorialMatchDone" -> session.withStep(Step.PASSIVE_EXAMPLE);
            case "tutorialShowPassiveInvite" -> session.withStep(Step.FINISH);
            case "tutorialFinish" -> null;
            default -> session;
        };

        if (id.equals("tutorialFinish")) {
            TutorialSessionRepository.delete(user.getId());
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadStartPage();
            return;
        }
        if (next == session) return;
        TutorialSessionRepository.save(next);
        event.deferEdit().queue();
        render(event.getMessage(), user, next);
    }

    public static void handleSelection(StringSelectInteractionEvent event, UserObject user) {
        Session session = TutorialSessionRepository.get(user.getId());
        if (session == null || event.getValues().isEmpty()) {
            event.reply(t(user, "Tutorial.Setup.Expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getComponentId().equals("tutorialGames")) {
            List<Integer> allowed = selectableModes().stream().map(GameObject::getId).toList();
            List<Integer> selected;
            try {
                selected = event.getValues().stream().map(Integer::parseInt)
                        .filter(allowed::contains).distinct().toList();
            } catch (NumberFormatException exception) {
                selected = List.of();
            }
            if (selected.isEmpty()) {
                event.reply(t(user, "Tutorial.Setup.Games.Invalid")).setEphemeral(true).queue();
                return;
            }
            Session next = new Session(user.getId(), Step.PROFILE_SETUP, selected, 0, 0, false, 0);
            TutorialSessionRepository.save(next);
            event.deferEdit().queue();
            render(event.getMessage(), user, next);
            return;
        }
        if (event.getComponentId().equals("tutorialPartyInvite")) {
            beginPartyInvitation(event, user, session);
            return;
        }
        if (event.getComponentId().equals("tutorialPartyKick")) {
            if (session.step() != Step.PARTY_ACTIVE || session.partySize() != 2) return;
            Session next = session.withPartySize(1).withStep(Step.PARTY_CREATED);
            TutorialSessionRepository.save(next);
            event.deferEdit().queue();
            renderPartyCreated(event.getMessage(), user, t(user, "Party.Kick.Success"));
            return;
        }
        if (event.getComponentId().equals("tutorialMatchPlayers")) {
            if (session.step() != Step.MATCH_PARTICIPANTS || event.getValues().size() < 2) return;
            Session next = session.withMatchAdded(true).withStep(Step.MATCH_RECORDED);
            TutorialSessionRepository.save(next);
            event.reply(t(user, "Match.Submitted")).setEphemeral(true).queue();
            render(event.getMessage(), user, next);
            return;
        }
        if (event.getComponentId().startsWith("tutorialClanSelect-")) {
            beginClanInvitations(event, user, session);
            return;
        }
        if (event.getComponentId().equals("tutorialLobbyGame")) {
            int gameId;
            try {
                gameId = Integer.parseInt(event.getValues().getFirst());
            } catch (NumberFormatException exception) {
                return;
            }
            if (!session.modeIds().contains(gameId)) {
                event.reply(t(user, "Tutorial.Setup.Games.Invalid")).setEphemeral(true).queue();
                return;
            }
            Session next = session.withLobbyGame(gameId).withStep(Step.LOBBY_ACTIVE);
            TutorialSessionRepository.save(next);
            event.deferEdit().queue();
            render(event.getMessage(), user, next);
        }
    }

    public static void handleModal(ModalInteractionEvent event, UserObject user) {
        Session session = TutorialSessionRepository.get(user.getId());
        if (session == null) {
            event.reply(t(user, "Tutorial.Setup.Expired")).setEphemeral(true).queue();
            return;
        }
        if (!event.getModalId().equals("tutorialInviteFriend")) return;
        String username = event.getValue("username") == null ? ""
                : event.getValue("username").getAsString().trim();
        if (!username.equalsIgnoreCase("Tutorial123")) {
            event.reply(t(user, "Lobby.InviteFriend.UNAVAILABLE")).setEphemeral(true).queue();
            return;
        }
        if (event.getMessage() == null) {
            event.reply(t(user, "Lobby.InviteFriend.UNAVAILABLE")).setEphemeral(true).queue();
            return;
        }
        beginFriendInvitation(event, user, session);
    }

    public static void profileConfigured(Message message, UserObject user) {
        Session session = TutorialSessionRepository.get(user.getId());
        if (session == null || session.step() != Step.PROFILE_SETUP) return;
        int nextIndex = session.profileIndex() + 1;
        Session next = nextIndex < session.modeIds().size()
                ? session.withProfileIndex(nextIndex)
                : session.withProfileIndex(nextIndex).withStep(Step.PARTY_INTRO);
        TutorialSessionRepository.save(next);
        render(message, user, next);
    }

    public static void profileCancelled(Message message, UserObject user) {
        resume(message, user);
    }

    private static void render(Message message, UserObject user, Session session) {
        switch (session.step()) {
            case WELCOME -> edit(message, user, "Tutorial.Setup.Welcome.Title",
                    "Tutorial.Setup.Welcome.Description",
                    List.of(ActionRow.of(Button.success("tutorialBegin",
                            t(user, "Tutorial.Setup.Button.Start")))), true);
            case GAME_SELECTION -> renderGameSelection(message, user);
            case PROFILE_SETUP -> renderProfileSetup(message, user, session);
            case PARTY_INTRO -> edit(message, user, "Tutorial.Setup.Party.Title",
                    "Tutorial.Setup.Party.Intro",
                    List.of(ActionRow.of(Button.success("tutorialCreateParty",
                            t(user, "Tutorial.Setup.Party.Create")))), true);
            case PARTY_CREATED -> renderPartyCreated(message, user);
            case PARTY_INVITE_PENDING -> renderPartyPending(message, user);
            case PARTY_ACTIVE -> renderPartyActive(message, user);
            case LOBBY_SELECTION -> renderLobbySelection(message, user, session);
            case LOBBY_ACTIVE -> {
                int initialPlayers = Math.max(1, session.partySize());
                if (initialPlayers == 2)
                    renderLobby(message, user, session, 2, "Tutorial.Setup.Lobby.CreatedWithParty",
                            Button.success("tutorialInviteClan", t(user, "Lobby.Button.InviteClan")));
                else
                    renderLobby(message, user, session, 1, "Tutorial.Setup.Lobby.CreatedWithoutParty",
                            Button.success("tutorialInviteFriend", t(user, "Lobby.Button.InviteFriend")));
            }
            case FRIEND_INVITES_PENDING -> renderLobbyPending(message, user, session, 1,
                    "Tutorial.Setup.Lobby.FriendPending");
            case FRIEND_INVITED -> renderLobby(message, user, session, 2, "Tutorial.Setup.Lobby.FriendAccepted",
                    Button.success("tutorialInviteClan", t(user, "Lobby.Button.InviteClan")));
            case CLAN_INVITES_PENDING -> renderLobbyPending(message, user, session, 2,
                    "Tutorial.Setup.Lobby.ClanPending");
            case CLAN_INVITED -> renderLobby(message, user, session, 3, "Tutorial.Setup.Lobby.ClanResult",
                    Button.success("tutorialEnablePassive", t(user, "Lobby.Button.ActivatePassive")));
            case PASSIVE_INVITES_PENDING -> renderLobbyPending(message, user, session, 3,
                    "Tutorial.Setup.Lobby.PassivePending");
            case LOBBY_FULL -> renderFullLobby(message, user, session);
            case MATCH_ENTRY -> renderMatchEntry(message, user, session);
            case MATCH_PARTICIPANTS -> renderMatchParticipants(message, user);
            case MATCH_RECORDED -> renderRecordedMatch(message, user);
            case PASSIVE_EXAMPLE -> renderPassiveExample(message, user, session);
            case FINISH -> renderFinalPassiveInvitation(message, user, session);
            case FINAL_LOBBY_MEMBER -> renderFinalMemberLobby(message, user, session);
            case SUMMARY -> edit(message, user, "Tutorial.Setup.Finish.Title",
                    "Tutorial.Setup.Finish.Description",
                    List.of(ActionRow.of(Button.success("tutorialFinish",
                            t(user, "Tutorial.Setup.Button.OpenManage")))), true);
        }
    }

    private static void renderGameSelection(Message message, UserObject user) {
        List<GameObject> modes = selectableModes();
        if (modes.isEmpty()) {
            edit(message, user, "Tutorial.Setup.Games.Title", "Tutorial.Setup.Games.None", List.of(), true);
            return;
        }
        List<SelectOption> options = modes.stream().limit(25)
                .map(game -> SelectOption.of(trim(displayName(game), 100), String.valueOf(game.getId())))
                .toList();
        edit(message, user, "Tutorial.Setup.Games.Title", "Tutorial.Setup.Games.Description",
                List.of(ActionRow.of(StringSelectMenu.create("tutorialGames")
                        .setPlaceholder(t(user, "Tutorial.Setup.Games.Select"))
                        .setMinValues(1).setMaxValues(options.size()).addOptions(options).build())), true);
    }

    private static void renderProfileSetup(Message message, UserObject user, Session session) {
        int gameId = session.modeIds().get(session.profileIndex());
        Map<String, String> values = Map.of(
                "%game%", displayName(GameRepository.get(gameId)),
                "%current%", String.valueOf(session.profileIndex() + 1),
                "%total%", String.valueOf(session.modeIds().size()));
        edit(message, user, "Tutorial.Setup.Profiles.Title", "Tutorial.Setup.Profiles.Description",
                values, List.of(ActionRow.of(Button.success("tutorialConfigureProfile",
                        trim(t(user, "Tutorial.Setup.Profiles.Configure", Map.of("%game%",
                                displayName(GameRepository.get(gameId)))), 80)))), true);
    }

    private static void renderPartyCreated(Message message, UserObject user) {
        renderPartyCreated(message, user, null);
    }

    private static void renderPartyCreated(Message message, UserObject user, String systemNotice) {
        editPartyManagement(message, user, "👑 " + user.getUsername(), 1,
                systemNotice, t(user, "Tutorial.Setup.Party.Created"),
                List.of(
                        ActionRow.of(StringSelectMenu.create("tutorialPartyInvite")
                                .setPlaceholder(t(user, "Party.Invite.Dropdown"))
                                .addOption("Tutorial123", "Tutorial123").build()),
                        partyNavigationRow(user, false)));
    }

    private static void renderPartyActive(Message message, UserObject user) {
        editPartyManagement(message, user, "👑 " + user.getUsername() + "\nTutorial123", 2,
                null, t(user, "Tutorial.Setup.Party.Active"),
                List.of(
                        ActionRow.of(StringSelectMenu.create("tutorialPartyKick")
                                .setPlaceholder(t(user, "Party.Kick.Select"))
                                .addOption("Tutorial123", "Tutorial123").build()),
                        partyNavigationRow(user, true)));
    }

    private static void renderPartyPending(Message message, UserObject user) {
        editPartyManagement(message, user, "👑 " + user.getUsername(), 1,
                t(user, "Party.Invite.Success"), t(user, "Tutorial.Setup.Party.Pending"),
                List.of(
                        ActionRow.of(StringSelectMenu.create("tutorialPartyInvite")
                                .setPlaceholder(t(user, "Party.Invite.Dropdown"))
                                .addOption("Tutorial123", "Tutorial123").setDisabled(true).build()),
                        partyNavigationRow(user, false)));
    }

    private static ActionRow partyNavigationRow(UserObject user, boolean hasMember) {
        return ActionRow.of(
                Button.danger("tutorialPartyLeave", t(user, "Party.Button.Leave")),
                Button.primary("tutorialPartyContinue", t(user, hasMember
                        ? "Tutorial.Setup.Party.Continue" : "UserProfile.Button.Back")));
    }

    private static void renderLobbySelection(Message message, UserObject user, Session session) {
        List<SelectOption> options = session.modeIds().stream().map(GameRepository::get)
                .filter(java.util.Objects::nonNull)
                .map(game -> SelectOption.of(trim(displayName(game), 100), String.valueOf(game.getId()))).toList();
        edit(message, user, "Tutorial.Setup.Lobby.SelectTitle", "Tutorial.Setup.Lobby.SelectDescription",
                List.of(ActionRow.of(StringSelectMenu.create("tutorialLobbyGame")
                        .setPlaceholder(t(user, "Tutorial.Setup.Lobby.SelectGame"))
                        .addOptions(options).build())), true);
    }

    private static void renderLobby(Message message, UserObject user, Session session, int players,
                                    String noticeKey, Button action) {
        editLobbyView(message, user, session, players, action, noticeKey);
    }

    private static void renderLobbyPending(Message message, UserObject user, Session session, int players,
                                           String noticeKey) {
        Button pendingButton = switch (session.step()) {
            case FRIEND_INVITES_PENDING -> Button.success("tutorialInviteFriend",
                    t(user, "Lobby.Button.InviteFriend")).withDisabled(true);
            case CLAN_INVITES_PENDING -> Button.success("tutorialInviteClan",
                    t(user, "Lobby.Button.InviteClan")).withDisabled(true);
            default -> Button.secondary("tutorialEnablePassive",
                    t(user, "Lobby.Button.DeactivatePassive")).withDisabled(true);
        };
        editLobbyView(message, user, session, players, pendingButton, noticeKey);
    }

    private static void renderFullLobby(Message message, UserObject user, Session session) {
        editLobbyView(message, user, session, TUTORIAL_CAPACITY,
                Button.danger("tutorialCloseLobby", t(user, "Lobby.Button.Close")),
                "Tutorial.Setup.Lobby.Full");
    }

    private static void renderMatchEntry(Message message, UserObject user, Session session) {
        Map<String, String> values = Map.of(
                "%deadline%", "<t:" + (System.currentTimeMillis() / 1000 + 3600) + ":R>",
                "%matches%", t(user, "Match.Summary.None"));
        message.editMessageEmbeds(new EmbedCreator().setTitle(t(user, "Match.Entry.Title"))
                        .setDescription(t(user, "Match.Entry.Description", values)).build(),
                        guideEmbed(user, "Tutorial.Setup.Match.Description",
                                Map.of("%game%", displayName(GameRepository.get(session.lobbyGameId())))))
                .setComponents(ActionRow.of(
                                Button.primary("tutorialMatchAdd", t(user, "Match.Button.AddAnother")),
                                Button.success("tutorialMatchSkip", t(user, "Match.Button.Done"))),
                        ActionRow.of(Button.danger("tutorialExit",
                                t(user, "Tutorial.Setup.Button.Exit"))))
                .queue();
    }

    private static void renderMatchParticipants(Message message, UserObject user) {
        List<SelectOption> players = List.of(user.getUsername(), "Tutorial123", "TutorialClanOne",
                        "TutorialQueue01", "TutorialQueue02").stream()
                .map(name -> SelectOption.of(name, name)).toList();
        message.editMessageEmbeds(new EmbedCreator().setTitle(t(user, "Match.Participants.Title"))
                        .setDescription(t(user, "Match.Participants.Description")).build(),
                        guideEmbed(user, "Tutorial.Setup.Match.ParticipantsGuide", Map.of()))
                .setComponents(ActionRow.of(StringSelectMenu.create("tutorialMatchPlayers")
                                .setPlaceholder(t(user, "Match.Participants.Select"))
                                .setMinValues(2).setMaxValues(players.size()).addOptions(players).build()),
                        ActionRow.of(Button.danger("tutorialExit",
                                t(user, "Tutorial.Setup.Button.Exit"))))
                .queue();
    }

    private static void renderRecordedMatch(Message message, UserObject user) {
        Map<String, String> values = Map.of(
                "%deadline%", "<t:" + (System.currentTimeMillis() / 1000 + 3600) + ":R>",
                "%matches%", "**#1**\n" + t(user, "Match.Summary.NoTeamValues"));
        message.editMessageEmbeds(new EmbedCreator().setTitle(t(user, "Match.Entry.Title"))
                        .setDescription(t(user, "Match.Entry.Description", values)).build(),
                        guideEmbed(user, "Tutorial.Setup.Match.RecordedGuide", Map.of()))
                .setComponents(ActionRow.of(
                                Button.primary("tutorialMatchAdd", t(user, "Match.Button.AddAnother")),
                                Button.success("tutorialMatchDone", t(user, "Match.Button.Done"))),
                        ActionRow.of(Button.danger("tutorialExit",
                                t(user, "Tutorial.Setup.Button.Exit"))))
                .queue();
    }

    private static void renderPassiveExample(Message message, UserObject user, Session session) {
        Map<String, String> values = Map.of(
                "%game%", displayName(GameRepository.get(session.lobbyGameId())),
                "%matchNotice%", t(user, session.matchAdded()
                        ? "Tutorial.Setup.Match.Added" : "Tutorial.Setup.Match.NotAdded"));
        edit(message, user, "Tutorial.Setup.Passive.Title", "Tutorial.Setup.Passive.Description", values,
                List.of(ActionRow.of(Button.success("tutorialShowPassiveInvite",
                        t(user, "Tutorial.Setup.Passive.ShowInvite")))), true);
    }

    private static Map<String, String> lobbyValues(UserObject user, Session session, int players) {
        String members = switch (players) {
            case 2 -> "\n• Tutorial123";
            case 3 -> "\n• Tutorial123\n• TutorialClanOne";
            case 4 -> "\n• Tutorial123\n• TutorialClanOne\n• TutorialQueue01";
            case 5 -> "\n• Tutorial123\n• TutorialClanOne\n• TutorialQueue01\n• TutorialQueue02";
            default -> "";
        };
        GameProfile profile = GameProfileRepository.get(user.getId(), session.lobbyGameId());
        return Map.of("%game%", displayName(GameRepository.get(session.lobbyGameId())),
                "%players%", String.valueOf(players), "%capacity%", String.valueOf(TUTORIAL_CAPACITY),
                "%members%", members,
                "%platform%", profile == null ? "-" : profile.platform(),
                "%region%", profile == null ? "-" : profile.region());
    }

    private static void beginPartyInvitation(ButtonInteractionEvent event, UserObject user, Session session) {
        if (!startPartyInvitation(event.getMessage(), user, session)) return;
        event.deferEdit().queue();
    }

    private static void beginPartyInvitation(StringSelectInteractionEvent event, UserObject user, Session session) {
        if (!startPartyInvitation(event.getMessage(), user, session)) return;
        event.deferEdit().queue();
    }

    private static boolean startPartyInvitation(Message message, UserObject user, Session session) {
        if (session.step() != Step.PARTY_CREATED || session.partySize() != 1) return false;
        Session pending = session.withStep(Step.PARTY_INVITE_PENDING);
        TutorialSessionRepository.save(pending);
        render(message, user, pending);
        CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS)
                .execute(() -> completePending(message, user, pending));
        return true;
    }

    private static void beginFriendInvitation(ModalInteractionEvent event, UserObject user, Session session) {
        if (session.step() != Step.LOBBY_ACTIVE || session.partySize() == 2) return;
        Session pending = session.withStep(Step.FRIEND_INVITES_PENDING);
        TutorialSessionRepository.save(pending);
        event.reply(t(user, "Lobby.InviteFriend.INVITED")).setEphemeral(true).queue();
        render(event.getMessage(), user, pending);
        CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS)
                .execute(() -> completePending(event.getMessage(), user, pending));
    }

    private static void showClanSelection(ButtonInteractionEvent event, UserObject user, Session session) {
        if (session.step() != Step.LOBBY_ACTIVE && session.step() != Step.FRIEND_INVITED) return;
        String description = t(user, "Lobby.InviteClan.Option", Map.of(
                "%role%", "MEMBER", "%members%", "2"));
        event.replyEmbeds(new EmbedCreator().setTitle(t(user, "Lobby.InviteClan.Title"))
                        .setDescription(t(user, "Lobby.InviteClan.Description",
                                Map.of("%page%", "1", "%pages%", "1"))).build())
                .setEphemeral(true)
                .setComponents(ActionRow.of(StringSelectMenu.create(
                                "tutorialClanSelect-" + event.getMessageId())
                        .setPlaceholder(t(user, "Lobby.InviteClan.Select"))
                        .addOption("TutorialClan", "TutorialClan", description).build()))
                .queue();
    }

    private static void beginClanInvitations(StringSelectInteractionEvent event, UserObject user,
                                             Session session) {
        if (session.step() != Step.LOBBY_ACTIVE && session.step() != Step.FRIEND_INVITED) return;
        Session pending = session.withStep(Step.CLAN_INVITES_PENDING);
        TutorialSessionRepository.save(pending);
        event.editMessage(t(user, "Lobby.InviteClan.Sent", Map.of("%count%", "2")))
                .setEmbeds().setComponents().queue();
        String messageId = event.getComponentId().substring("tutorialClanSelect-".length());
        event.getChannel().retrieveMessageById(messageId).queue(message -> {
            render(message, user, pending);
            CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS)
                    .execute(() -> completePending(message, user, pending));
        });
    }

    private static void beginPassiveInvitations(ButtonInteractionEvent event, UserObject user, Session session) {
        if (session.step() != Step.CLAN_INVITED) return;
        Session pending = session.withStep(Step.PASSIVE_INVITES_PENDING);
        TutorialSessionRepository.save(pending);
        event.reply(t(user, "Lobby.PassiveQueue.Started", Map.of("%count%", "2")))
                .setEphemeral(true).queue();
        render(event.getMessage(), user, pending);
        CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS)
                .execute(() -> completePending(event.getMessage(), user, pending));
    }

    private static void completePending(Message message, UserObject user, Session expected) {
        Session current = TutorialSessionRepository.get(user.getId());
        if (current == null || current.step() != expected.step()) return;
        Session next = switch (current.step()) {
            case PARTY_INVITE_PENDING -> current.withPartySize(2).withStep(Step.PARTY_ACTIVE);
            case FRIEND_INVITES_PENDING -> current.withStep(Step.FRIEND_INVITED);
            case CLAN_INVITES_PENDING -> current.withStep(Step.CLAN_INVITED);
            case PASSIVE_INVITES_PENDING -> current.withStep(Step.LOBBY_FULL);
            default -> current;
        };
        if (next == current) return;
        TutorialSessionRepository.save(next);
        render(message, user, next);
    }

    private static void renderFinalPassiveInvitation(Message message, UserObject user, Session session) {
        Map<String, String> values = new java.util.HashMap<>(lobbyValues(user, session, 4));
        values.put("%leader%", "TutorialQueue01");
        values.put("%lobbyId%", "TQ" + user.getId());
        values.put("%leaderRating%", t(user, "Rating.None"));
        values.put("%playerCount%", "4");
        values.put("%players%", finalPassivePlayerList(user, session));
        values.put("%languages%", communicationLanguages(user));
        values.put("%ranks%", rankLabel(user, session));
        values.put("%source%", t(user, "Lobby.Invitation.Source.PASSIVE_QUEUE"));
        String descriptionKey = GameMessageVisibility.showsRanks(session.lobbyGameId())
                ? "Lobby.Invitation.Description" : "Lobby.Invitation.DescriptionNoRank";
        message.editMessageEmbeds(new EmbedCreator()
                        .setTitle(t(user, "Lobby.Invitation.Title"))
                        .setDescription(t(user, descriptionKey, values)).build(),
                        guideEmbed(user, "Tutorial.Setup.Passive.InvitationGuide", Map.of()))
                .setComponents(
                        ActionRow.of(
                                Button.success("tutorialFinalPassiveAccept",
                                        t(user, "Lobby.Invitation.Accept")),
                                Button.danger("tutorialFinalPassiveDecline",
                                        t(user, "Lobby.Invitation.Decline"))),
                        ActionRow.of(Button.danger("tutorialExit",
                                t(user, "Tutorial.Setup.Button.Exit"))))
                .queue();
    }

    private static void renderFinalMemberLobby(Message message, UserObject user, Session session) {
        Map<String, String> values = new java.util.HashMap<>(lobbyValues(user, session, 5));
        values.put("%lobbyId%", "TQ" + user.getId());
        values.put("%languages%", communicationLanguages(user));
        values.put("%ranks%", rankLabel(user, session));
        values.put("%voiceInvite%", t(user, "Lobby.View.VoicePreparing"));
        values.put("%playerList%", finalMemberPlayerList(user, session));
        String descriptionKey = GameMessageVisibility.showsRanks(session.lobbyGameId())
                ? "Lobby.View.Description" : "Lobby.View.DescriptionNoRank";
        message.editMessageEmbeds(new EmbedCreator()
                        .setTitle(t(user, "Lobby.View.Title", values))
                        .setDescription(t(user, descriptionKey, values)).build(),
                        guideEmbed(user, "Tutorial.Setup.Passive.MemberGuide", Map.of()))
                .setComponents(
                        ActionRow.of(
                                Button.danger("tutorialFinalLobbyLeave", t(user, "Lobby.Button.Leave")),
                                Button.primary("tutorialFinalLobbyBack",
                                        t(user, "UserProfile.Button.Back")).withDisabled(true)),
                        ActionRow.of(Button.danger("tutorialExit",
                                t(user, "Tutorial.Setup.Button.Exit"))))
                .queue();
    }

    private static void completeTutorial(ButtonInteractionEvent event, UserObject user, String actionKey) {
        TutorialSessionRepository.delete(user.getId());
        event.reply(t(user, actionKey) + "\n\n" + t(user, "Tutorial.Complete"))
                .setEphemeral(true).queue();
        new UserControlManager(event.getMessage(), user).loadStartPage();
    }

    private static void editPartyManagement(Message message, UserObject user, String members, int count,
                                            String systemNotice, String guide, List<ActionRow> rows) {
        Map<String, String> values = Map.of("%members%", members, "%count%", String.valueOf(count),
                "%notice%", systemNotice == null ? "" : "\n\n" + systemNotice);
        message.editMessageEmbeds(new EmbedCreator()
                        .setTitle(t(user, "Party.Manage.title"))
                        .setDescription(t(user, "Party.Manage.description", values)).build(),
                        guideEmbed(user, null, guide))
                .setComponents(rows).queue();
    }

    private static void editLobbyView(Message message, UserObject user, Session session, int players,
                                      Button action, String guideKey) {
        Map<String, String> values = new java.util.HashMap<>(lobbyValues(user, session, players));
        values.put("%lobbyId%", tutorialLobbyId(user));
        values.put("%languages%", communicationLanguages(user));
        values.put("%ranks%", rankLabel(user, session));
        values.put("%voiceInvite%", t(user, players == TUTORIAL_CAPACITY
                ? "Lobby.View.VoicePreparing" : "Lobby.View.VoiceNotReady"));
        values.put("%playerList%", playerList(user, session, players));
        String descriptionKey = GameMessageVisibility.showsRanks(session.lobbyGameId())
                ? "Lobby.View.Description" : "Lobby.View.DescriptionNoRank";
        message.editMessageEmbeds(new EmbedCreator()
                        .setTitle(t(user, "Lobby.View.Title", values))
                        .setDescription(t(user, descriptionKey, values)).build(),
                        guideEmbed(user, guideKey, Map.of()))
                .setComponents(lobbyControls(user, action, players)).queue();
    }

    private static List<ActionRow> lobbyControls(UserObject user, Button action, int players) {
        List<ActionRow> rows = new ArrayList<>();
        if (players >= TUTORIAL_CAPACITY) {
            rows.add(ActionRow.of(
                    Button.primary("tutorialLobbySettings", t(user, "Lobby.Button.Settings")).withDisabled(true),
                    Button.primary("tutorialMatchEntry", t(user, "Match.Button.Add")).withDisabled(true),
                    action));
        } else {
            String activeId = action.getCustomId();
            rows.add(ActionRow.of(
                    Button.success("tutorialInviteFriend", t(user, "Lobby.Button.InviteFriend"))
                            .withDisabled(!"tutorialInviteFriend".equals(activeId)),
                    Button.success("tutorialInviteClan", t(user, "Lobby.Button.InviteClan"))
                            .withDisabled(!"tutorialInviteClan".equals(activeId))));
            rows.add(ActionRow.of(
                    Button.secondary("tutorialEnablePassive", t(user,
                                    "tutorialEnablePassive".equals(activeId)
                                            ? "Lobby.Button.ActivatePassive"
                                            : "Lobby.Button.DeactivatePassive"))
                            .withDisabled(!"tutorialEnablePassive".equals(activeId)),
                    Button.primary("tutorialLobbySettings", t(user, "Lobby.Button.Settings")).withDisabled(true),
                    Button.danger("tutorialCloseLobby", t(user, "Lobby.Button.Close")).withDisabled(true)));
        }
        rows.add(ActionRow.of(Button.danger("tutorialExit", t(user, "Tutorial.Setup.Button.Exit"))));
        return rows;
    }

    private static String communicationLanguages(UserObject user) {
        return CommunicationLanguageRepository.getForUser(user.getId()).stream()
                .map(language -> CommunicationLanguageNames.displayNameWithFlag(language.code(), user.getLanguage()))
                .reduce((first, next) -> first + ", " + next)
                .orElse(t(user, "Lobby.View.AnyLanguage"));
    }

    private static String rankLabel(UserObject user, Session session) {
        GameProfile profile = GameProfileRepository.get(user.getId(), session.lobbyGameId());
        if (profile == null) return t(user, "Lobby.Invitation.RankUnknown");
        return GameOptionRepository.get(session.lobbyGameId(), GameOption.Type.RANK).stream()
                .filter(rank -> rank.sortOrder() == profile.rankValue()).map(GameOption::name)
                .findFirst().orElse(profile.rankValue() == 0 ? t(user, "Lobby.View.AnyRank")
                        : String.valueOf(profile.rankValue()));
    }

    private static String playerList(UserObject user, Session session, int players) {
        List<String> names = new ArrayList<>(List.of(user.getUsername(), "Tutorial123",
                "TutorialClanOne", "TutorialQueue01", "TutorialQueue02"));
        String rank = rankLabel(user, session);
        boolean ranked = GameMessageVisibility.showsRanks(session.lobbyGameId());
        return names.subList(0, Math.min(players, names.size())).stream()
                .map(name -> "• " + name + (ranked ? " · " + rank : "") + " · ⭐ "
                        + t(user, "Rating.None"))
                .reduce((first, next) -> first + "\n" + next).orElse("-");
    }

    private static String finalPassivePlayerList(UserObject user, Session session) {
        String rank = rankLabel(user, session);
        boolean ranked = GameMessageVisibility.showsRanks(session.lobbyGameId());
        return List.of("TutorialQueue01", "TutorialQueue02", "TutorialQueue03", "TutorialQueue04")
                .stream().map(name -> "• " + name + (ranked ? " · " + rank : "") + " · ⭐ "
                        + t(user, "Rating.None"))
                .reduce((first, next) -> first + "\n" + next).orElse("-");
    }

    private static String finalMemberPlayerList(UserObject user, Session session) {
        String rank = rankLabel(user, session);
        boolean ranked = GameMessageVisibility.showsRanks(session.lobbyGameId());
        return List.of("TutorialQueue01", "TutorialQueue02", "TutorialQueue03",
                        "TutorialQueue04", user.getUsername()).stream()
                .map(name -> "• " + name + (ranked ? " · " + rank : "") + " · ⭐ "
                        + t(user, "Rating.None"))
                .reduce((first, next) -> first + "\n" + next).orElse("-");
    }

    private static String tutorialLobbyId(UserObject user) {
        return "T" + user.getId();
    }

    private static boolean isPending(Step step) {
        return step == Step.PARTY_INVITE_PENDING || step == Step.FRIEND_INVITES_PENDING
                || step == Step.CLAN_INVITES_PENDING || step == Step.PASSIVE_INVITES_PENDING;
    }

    private static net.dv8tion.jda.api.entities.MessageEmbed guideEmbed(
            UserObject user, String key, Map<String, String> replacements) {
        return new EmbedCreator().setTitle("[Tutorial] " + t(user, "Tutorial.Title"))
                .setDescription(t(user, key, replacements)).build();
    }

    private static net.dv8tion.jda.api.entities.MessageEmbed guideEmbed(
            UserObject user, String ignored, String description) {
        return new EmbedCreator().setTitle("[Tutorial] " + t(user, "Tutorial.Title"))
                .setDescription(description).build();
    }

    private static void pinManagementMessage(Message message) {
        if (message.isPinned()) return;
        message.pin().queue(ignored -> message.getChannel().getHistory().retrievePast(10).queue(messages ->
                        messages.stream()
                                .filter(candidate -> candidate.getType() == MessageType.CHANNEL_PINNED_ADD)
                                .filter(candidate -> !candidate.getTimeCreated()
                                        .isBefore(message.getTimeCreated()))
                                .forEach(candidate -> candidate.delete().queue(null, error -> {})),
                error -> {}), error -> {});
    }

    private static void edit(Message message, UserObject user, String titleKey, String descriptionKey,
                             List<ActionRow> rows, boolean exit) {
        edit(message, user, titleKey, descriptionKey, Map.of(), rows, exit);
    }

    private static void edit(Message message, UserObject user, String titleKey, String descriptionKey,
                             Map<String, String> replacements, List<ActionRow> rows, boolean exit) {
        List<ActionRow> components = new ArrayList<>(rows);
        if (exit) components.add(ActionRow.of(Button.danger("tutorialExit",
                t(user, "Tutorial.Setup.Button.Exit"))));
        message.editMessageEmbeds(new EmbedCreator().setTitle(t(user, titleKey, replacements))
                        .setDescription(t(user, descriptionKey, replacements)).build())
                .setComponents(components).queue();
    }

    private static List<GameObject> selectableModes() {
        List<GameObject> result = new ArrayList<>();
        for (GameObject main : GameRepository.getAllMainGames().stream()
                .filter(GameObject::isActive).sorted(Comparator.comparing(GameObject::getName)).toList()) {
            List<GameObject> subGames = GameRepository.getSubGames(main.getId()).stream()
                    .filter(GameObject::isActive).sorted(Comparator.comparing(GameObject::getName)).toList();
            if (subGames.isEmpty()) result.add(main);
            else result.addAll(subGames);
        }
        return new ArrayList<>(new LinkedHashSet<>(result));
    }

    private static String displayName(GameObject game) {
        if (game == null) return "-";
        GameObject parent = game.getSubGameFrom();
        return parent == null ? game.getName() : parent.getName() + " · " + game.getName();
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static String t(UserObject user, String key) {
        return LanguageManager.getMessageForUser(key, user.getId());
    }

    private static String t(UserObject user, String key, Map<String, String> replacements) {
        return LanguageManager.getMessageForUser(key, user.getId(), replacements);
    }
}
