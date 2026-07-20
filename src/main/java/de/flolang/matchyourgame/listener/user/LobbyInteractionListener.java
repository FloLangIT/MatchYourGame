package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.lobby.*;
import de.flolang.matchyourgame.database.game.GameOption;
import de.flolang.matchyourgame.database.game.GameOptionRepository;
import de.flolang.matchyourgame.database.game.RankCompatibilityRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.language.CommunicationLanguageNames;
import de.flolang.matchyourgame.manager.UserControlManager;
import de.flolang.matchyourgame.manager.ManagementMessageUpdater;
import de.flolang.matchyourgame.manager.lobby.LobbyJoinResult;
import de.flolang.matchyourgame.manager.match.MatchService;
import de.flolang.matchyourgame.manager.party.PartyService;
import de.flolang.matchyourgame.manager.lobby.GameSelectionWizard;
import de.flolang.matchyourgame.manager.lobby.LobbyCapacityRules;
import de.flolang.matchyourgame.manager.lobby.LobbyService;
import de.flolang.matchyourgame.logging.DiscordLogService;
import de.flolang.matchyourgame.database.review.ReviewAssignment;
import de.flolang.matchyourgame.database.review.ReviewRepository;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class LobbyInteractionListener extends ListenerAdapter {
    private final PartyService parties = new PartyService();
    private final ConcurrentHashMap<String, PendingLobbySettings> pendingLobbySettings = new ConcurrentHashMap<>();

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getButton().getCustomId();
        if (id == null) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;

        if (id.equals("createLobby")) {
            LobbyObject existing = LobbyRepository.getActiveForUser(user.getId());
            if (existing == null) Main.gameSelectionWizard.start(event, user, GameSelectionWizard.Flow.LOBBY);
            else { event.deferEdit().queue(); new UserControlManager(event.getMessage(), user).loadLobbyPage(existing); }
        } else if (id.equals("gameProfiles")) {
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadGameProfilesPage();
        } else if (id.startsWith("gameProfilesPage-")) {
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadGameProfilesPage(suffix(id));
        } else if (id.equals("gameProfileConfigure")) {
            Main.gameSelectionWizard.start(event, user, GameSelectionWizard.Flow.PROFILE_UPDATE);
        } else if (id.equals("communicationLanguages")) {
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadCommunicationLanguagesPage();
        } else if (id.equals("communicationLanguageAdd")) {
            event.replyModal(Modal.create("communicationLanguageAdd", t(user.getId(), "GameProfile.Languages.Modal.Title"))
                    .addComponents(input(t(user.getId(), "GameProfile.Languages.Modal.Language"), "language", "DE", true),
                            input(t(user.getId(), "GameProfile.Languages.Modal.Priority"), "priority", "1", true)).build()).queue();
        } else if (id.startsWith("gameProfileChanged-")) {
            Main.gameSelectionWizard.startProfileUpdate(event, user, suffix(id));
        } else if (id.startsWith("gameProfileUnchanged-")) {
            event.editMessage(t(user.getId(), "GameProfile.AfterLobby.ConfirmedUnchanged")).setComponents()
                    .queue(hook -> hook.deleteOriginal().queueAfter(5, TimeUnit.SECONDS));
        } else if (id.equals("passiveQ")) {
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadPassivePage();
        } else if (id.equals("passiveConfigure")) {
            Main.gameSelectionWizard.start(event, user, GameSelectionWizard.Flow.PASSIVE_QUEUE);
        } else if (id.equals("passiveGlobalToggle")) {
            PassiveQueueSettings settings = PassiveQueueSettingsRepository.get(user.getId());
            boolean enabled = !settings.enabled();
            if (enabled && SearchProfileRepository.getForUser(user.getId()).stream()
                    .noneMatch(de.flolang.matchyourgame.database.lobby.SearchProfile::passiveEnabled)) {
                event.reply(t(user.getId(), "PassiveQ.NoGamesSelected")).setEphemeral(true).queue();
                return;
            }
            PassiveQueueSettingsRepository.update(user.getId(), enabled, settings.syncOnlineStatus());
            DiscordLogService.action("PASSIVEQ_GLOBAL", "User " + user.getUsername() + " (#" + user.getId()
                    + ") hat PassiveQ global " + (enabled ? "aktiviert" : "deaktiviert"));
            ManagementMessageUpdater.refreshFriendActivity(user.getId());
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadPassivePage();
        } else if (id.equals("passiveGlobalShortcut")) {
            if (LobbyRepository.getActiveForUser(user.getId()) != null) {
                event.reply(t(user.getId(), "PassiveQ.Shortcut.ActiveLobby")).setEphemeral(true).queue();
                return;
            }
            PassiveQueueSettings settings = PassiveQueueSettingsRepository.get(user.getId());
            boolean enabled = !settings.enabled();
            if (enabled && SearchProfileRepository.getForUser(user.getId()).stream()
                    .noneMatch(de.flolang.matchyourgame.database.lobby.SearchProfile::passiveEnabled)) {
                event.reply(t(user.getId(), "PassiveQ.NoGamesSelected")).setEphemeral(true).queue();
                return;
            }
            PassiveQueueSettingsRepository.update(user.getId(), enabled, settings.syncOnlineStatus());
            DiscordLogService.action("PASSIVEQ_GLOBAL", "User " + user.getUsername() + " (#" + user.getId()
                    + ") hat PassiveQ global " + (enabled ? "aktiviert" : "deaktiviert"));
            ManagementMessageUpdater.refreshFriendActivity(user.getId());
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadStartPage();
        } else if (id.equals("passiveSyncToggle")) {
            PassiveQueueSettings settings = PassiveQueueSettingsRepository.get(user.getId());
            PassiveQueueSettingsRepository.update(user.getId(), settings.enabled(), !settings.syncOnlineStatus());
            ManagementMessageUpdater.refreshFriendActivity(user.getId());
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadPassivePage();
        } else if (id.equals("browseLobbies")) {
            event.replyModal(Modal.create("browseLobbies", t(user.getId(), "Lobby.Browse.Modal.Title"))
                    .addComponents(input(t(user.getId(), "Lobby.Browse.Modal.Game"), "game", "1", true)).build()).queue();
        } else if (id.equals("party")) {
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadPartyPage(0);
        } else if (id.equals("partyLeave")) {
            parties.leave(user.getId());
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadStartPage();
        } else if (id.startsWith("lobbyInviteFriend-")) {
            int lobbyId = suffix(id);
            event.replyModal(Modal.create("lobbyInviteFriend-" + lobbyId, t(user.getId(), "Lobby.InviteFriend.Modal.Title"))
                    .addComponents(input(t(user.getId(), "Lobby.InviteFriend.Modal.Username"), "username", "PlayerName", true)).build()).queue();
        } else if (id.startsWith("lobbyInviteClan-")) {
            int lobbyId = suffix(id);
            showClanPicker(event, user, lobbyId, 0, false);
        } else if (id.startsWith("lobbyClanPage-")) {
            String[] parts = id.split("-");
            showClanPicker(event, user, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), true);
        } else if (id.startsWith("lobbyTogglePassive-")) {
            togglePassiveQueue(event, suffix(id), user);
        } else if (id.startsWith("lobbySettings-")) {
            showLobbySettingsModal(event, suffix(id), user);
        } else if (id.startsWith("lobbySettingsRankRule-")) {
            applyPendingLobbySettings(event, id, user);
        } else if (id.startsWith("lobbyToggleClan-")) {
            event.reply(t(user.getId(), "Lobby.Clan.UseInviteButton")).setEphemeral(true).queue();
        } else if (id.startsWith("lobbyStartCurrent-")) {
            boolean started = Main.lobbyService.startWithCurrentPlayers(suffix(id), user.getId());
            event.reply(t(user.getId(), started
                            ? "Lobby.PassiveQueue.StartedCurrent" : "Lobby.PassiveQueue.ActionFailed"))
                    .setEphemeral(true).queue(ignored -> {
                        if (started) event.getMessage().delete().queue();
                    });
        } else if (id.startsWith("lobbyDissolve-")) {
            boolean dissolved = Main.lobbyService.dissolve(suffix(id), user.getId());
            event.reply(t(user.getId(), dissolved
                            ? "Lobby.PassiveQueue.Dissolved" : "Lobby.PassiveQueue.ActionFailed"))
                    .setEphemeral(true).queue(ignored -> {
                        if (dissolved) event.getMessage().delete().queue();
                    });
        } else if (id.startsWith("lobbyFindMerge-")) {
            showMergeCandidates(event, suffix(id), user);
        } else if (id.startsWith("lobbyJoin-")) {
            int lobbyId = suffix(id);
            replyJoin(event, Main.lobbyService.joinBrowse(lobbyId, user.getId()), lobbyId, user.getId());
        } else if (id.startsWith("browsePage-")) {
            String[] parts = id.split("-");
            int gameId = Integer.parseInt(parts[1]);
            int page = Integer.parseInt(parts[2]);
            SearchProfile profile = SearchProfileRepository.get(user.getId(), gameId);
            List<LobbyObject> lobbies = Main.lobbyService.browse(profile);
            event.editMessageEmbeds(browseEmbed(lobbies, page, user.getId())).setComponents(browseControls(lobbies, gameId, page, user.getId())).queue();
        } else if (id.startsWith("lobbyInviteAccept-")) {
            LobbyInvitation invitation = LobbyInvitationRepository.get(suffix(id));
            int lobbyId = invitation == null ? 0 : invitation.lobbyId();
            replyJoin(event, Main.lobbyService.acceptInvitation(suffix(id), user.getId()), lobbyId, user.getId());
            event.getMessage().delete().queue();
        } else if (id.startsWith("lobbyInviteDecline-")) {
            boolean declined = Main.lobbyService.declineInvitation(suffix(id), user.getId());
            event.reply(t(user.getId(), declined ? "Lobby.InvitationDeclined" : "Lobby.InvitationUnavailable"))
                    .setEphemeral(true).queue(ignored -> event.getMessage().delete().queue());
        } else if (id.startsWith("lobbyClose-")) {
            int lobbyId = suffix(id);
            if (!Main.lobbyService.close(lobbyId, user.getId())) {
                event.reply(t(user.getId(), "Lobby.OnlyHostClose")).setEphemeral(true).queue();
            } else {
                Main.reviewService.assignAfterLobby(lobbyId);
                event.replyEmbeds(Main.matchService.closureSummary(lobbyId, user.getId()))
                        .setComponents(Main.matchService.hostEntryComponents(lobbyId, user.getId()))
                        .queue(hook -> hook.retrieveOriginal().queue(message ->
                                Main.matchService.storeHostEntryMessage(lobbyId, message.getId())));
            }
        } else if (id.startsWith("reviewOpen-")) {
            int assignmentId = suffix(id);
            ReviewAssignment assignment = ReviewRepository.get(assignmentId);
            if (assignment == null || assignment.reviewerUserId() != user.getId() || assignment.completed()) {
                event.reply(t(user.getId(), "Review.NotAvailable")).setEphemeral(true).queue();
            } else {
                event.replyModal(Modal.create("reviewSubmit-" + assignmentId, t(user.getId(), "Review.Modal.Title")).addComponents(
                        input(t(user.getId(), "Review.Modal.Behavior"), "behavior", "5", true),
                        input(t(user.getId(), "Review.Modal.Teamplay"), "teamplay", "5", true),
                        input(t(user.getId(), "Review.Modal.Reliability"), "reliability", "5", true),
                        Label.of(t(user.getId(), "Review.Modal.Feedback"), TextInput.create("feedback", TextInputStyle.PARAGRAPH)
                                .setRequired(false).setMaxLength(1500).build())).build()).queue();
            }
        } else if (id.startsWith("matchBackToLobby-")) {
            int lobbyId = suffix(id);
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadLobbyPage(LobbyRepository.get(lobbyId));
        } else if (id.startsWith("matchEntryOverview-")) {
            int lobbyId = suffix(id);
            LobbyObject lobby = LobbyRepository.get(lobbyId);
            if (!canManageActiveMatches(lobby, user.getId())) {
                event.reply(t(user.getId(), "Match.CreateFailed")).setEphemeral(true).queue();
            } else event.editMessageEmbeds(Main.matchService.activeEntryEmbed(lobbyId, user.getId(), ""))
                    .setContent(null).setComponents(Main.matchService.activeEntryComponents(lobbyId, user.getId())).queue();
        } else if (id.startsWith("matchAdd-")) {
            int lobbyId = suffix(id);
            MatchService.EntrySession session = Main.matchService.start(lobbyId, user.getId());
            LobbyObject matchLobby = LobbyRepository.get(lobbyId);
            boolean closedEntry = matchLobby != null && matchLobby.getStatus() == LobbyStatus.CLOSED;
            if (session == null) {
                event.reply(t(user.getId(), "Match.CreateFailed")).setEphemeral(true).queue();
            } else {
                if (closedEntry) event.editMessageEmbeds(Main.matchService.closureSummary(lobbyId, user.getId()),
                                Main.matchService.participantSelectionEmbed(user.getId()))
                        .setContent(null).setComponents(Main.matchService.participantSelectionComponents(
                                session, user.getId(), false)).queue();
                else event.editMessageEmbeds(Main.matchService.activeEntryEmbed(lobbyId, user.getId(),
                                t(user.getId(), "Match.Participants.Description"))).setContent(null)
                        .setComponents(Main.matchService.participantSelectionComponents(session, user.getId(), true)).queue();
            }
        } else if (id.startsWith("matchNext-")) {
            int matchId = suffix(id);
            List<MatchService.StatTask> tasks = Main.matchService.nextBatch(matchId, user.getId());
            if (tasks.isEmpty()) {
                event.reply(t(user.getId(), "Match.NoStatsPending")).setEphemeral(true).queue();
            } else {
                Modal.Builder modal = Modal.create("matchStats-" + matchId, t(user.getId(), "Match.StatsModal.Title"));
                for (int i = 0; i < tasks.size(); i++) {
                    MatchService.StatTask task = tasks.get(i);
                    String label = task.label().length() > 45 ? task.label().substring(0, 45) : task.label();
                    TextInputStyle style = task.definition().valueType() == de.flolang.matchyourgame.database.game.GameStatDefinition.ValueType.TEXT
                            ? TextInputStyle.PARAGRAPH : TextInputStyle.SHORT;
                    modal.addComponents(Label.of(label, TextInput.create("v" + i, style)
                            .setPlaceholder(task.definition().valueType().name()).setRequired(true).setMaxLength(1500).build()));
                }
                event.replyModal(modal.build()).queue();
            }
        } else if (id.startsWith("matchBatchApprove-")) {
            int lobbyId = suffix(id);
            if (Main.matchService.confirmLobby(lobbyId, user.getId())) event.deferEdit().queue();
            else event.reply(t(user.getId(), "Match.Confirmation.Unavailable")).setEphemeral(true).queue();
        } else if (id.startsWith("matchCorrectionNext-")) {
            int matchId = suffix(id);
            if (Main.matchService.getCorrection(matchId, user.getId()) == null)
                event.reply(t(user.getId(), "Match.SessionUnavailable")).setEphemeral(true).queue();
            else event.replyModal(correctionModal(matchId, user.getId())).queue();
        } else if (id.startsWith("matchDone-")) {
            int lobbyId = suffix(id);
            if (!Main.matchService.finishLobbyEntry(lobbyId, user.getId()))
                event.reply(t(user.getId(), "Match.Entry.Unavailable")).setEphemeral(true).queue();
            else event.deferEdit().queue(hook -> hook.deleteOriginal().queue());
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (event.getValues().isEmpty()) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        if (event.getComponentId().startsWith("matchBatchEdit-")) {
            int matchId;
            try { matchId = Integer.parseInt(event.getValues().getFirst()); }
            catch (NumberFormatException exception) { return; }
            MatchService.CorrectionSession correction = Main.matchService.startCorrection(matchId, user.getId());
            if (correction == null) event.reply(t(user.getId(), "Match.Confirmation.Unavailable")).setEphemeral(true).queue();
            else event.replyModal(correctionModal(matchId, user.getId())).queue();
            return;
        }
        if (event.getComponentId().startsWith("matchPlayers-")) {
            int matchId = suffix(event.getComponentId());
            MatchService.EntrySession session = Main.matchService.getSession(matchId, user.getId());
            List<Integer> selected;
            try { selected = event.getValues().stream().map(Integer::parseInt).toList(); }
            catch (NumberFormatException exception) { return; }
            if (session == null || !Main.matchService.selectParticipants(matchId, user.getId(), selected)) {
                event.reply(t(user.getId(), "Match.SessionUnavailable")).setEphemeral(true).queue();
                return;
            }
            LobbyObject lobby = LobbyRepository.get(session.lobbyId());
            boolean closedEntry = lobby != null && lobby.getStatus() == LobbyStatus.CLOSED;
            if (session.complete()) {
                if (closedEntry) event.editMessageEmbeds(Main.matchService.closureSummary(session.lobbyId(), user.getId()))
                        .setContent(null).setComponents(Main.matchService.hostEntryComponents(session.lobbyId(), user.getId())).queue();
                else event.editMessageEmbeds(Main.matchService.activeEntryEmbed(session.lobbyId(), user.getId(),
                                t(user.getId(), "Match.Submitted"))).setContent(null)
                        .setComponents(Main.matchService.activeEntryComponents(session.lobbyId(), user.getId())).queue();
            } else if (closedEntry) event.editMessageEmbeds(Main.matchService.closureSummary(session.lobbyId(), user.getId()),
                            Main.matchService.entryProgressEmbed(user.getId(), t(user.getId(), "Match.Participants.Saved")))
                    .setContent(null).setComponents(ActionRow.of(Button.primary("matchNext-" + matchId,
                            t(user.getId(), "Match.Button.EnterStats")))).queue();
            else event.editMessageEmbeds(Main.matchService.activeEntryEmbed(session.lobbyId(), user.getId(),
                            t(user.getId(), "Match.Participants.Saved"))).setContent(null)
                    .setComponents(Main.matchService.activeEntryComponents(session.lobbyId(), user.getId(), matchId,
                            t(user.getId(), "Match.Button.EnterStats"))).queue();
            return;
        }
        if (event.getComponentId().startsWith("matchPlayerStats-")) {
            int lobbyId = suffix(event.getComponentId());
            int matchId;
            try { matchId = Integer.parseInt(event.getValues().getFirst()); }
            catch (NumberFormatException exception) { return; }
            LobbyObject lobby = LobbyRepository.get(lobbyId);
            var embed = Main.matchService.playerStatsEmbed(lobbyId, matchId, user.getId());
            if (!canManageActiveMatches(lobby, user.getId()) || embed == null)
                event.reply(t(user.getId(), "Match.SessionUnavailable")).setEphemeral(true).queue();
            else event.editMessageEmbeds(embed).setContent(null)
                    .setComponents(Main.matchService.playerStatsComponents(lobbyId, user.getId())).queue();
            return;
        }
        if (event.getComponentId().startsWith("gameProfileSelect-")) {
            String[] value = event.getValues().getFirst().split("\\|", 2);
            if (value.length != 2) return;
            Main.gameSelectionWizard.startExistingProfileUpdate(event, user, Integer.parseInt(value[0]), value[1]);
            return;
        }
        if (event.getComponentId().startsWith("lobbyMergeSelect-")) {
            int sourceLobbyId = suffix(event.getComponentId());
            int targetLobbyId = Integer.parseInt(event.getValues().getFirst());
            LobbyService.LobbyMergeResult result = Main.lobbyService.mergeLobbies(
                    sourceLobbyId, targetLobbyId, user.getId());
            event.reply(t(user.getId(), "Lobby.Merge.Result." + result.name(), Map.of(
                            "%targetLobby%", String.valueOf(targetLobbyId))))
                    .setEphemeral(true).queue(ignored -> {
                        if (result == LobbyService.LobbyMergeResult.MERGED) event.getMessage().delete().queue();
                    });
            return;
        }
        if (!event.getComponentId().startsWith("lobbyClanSelect-")) return;
        String[] parts = event.getComponentId().split("-");
        int lobbyId = Integer.parseInt(parts[1]);
        int clanId = Integer.parseInt(event.getValues().getFirst());
        int count = Main.lobbyService.inviteClan(user.getId(), lobbyId, clanId);
        event.editMessage(t(user.getId(), "Lobby.InviteClan.Sent",
                        Map.of("%count%", String.valueOf(count))))
                .setEmbeds().setComponents().queue();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        try {
            if (id.equals("communicationLanguageAdd")) {
                de.flolang.matchyourgame.database.profile.CommunicationLanguageRepository.upsert(user.getId(),
                        value(event, "language"), integer(event, "priority"));
                event.deferEdit().queue();
                new UserControlManager(event.getMessage(), user).loadCommunicationLanguagesPage();
            } else if (id.equals("browseLobbies")) {
                SearchProfile profile = SearchProfileRepository.get(user.getId(), integer(event, "game"));
                List<LobbyObject> lobbies = Main.lobbyService.browse(profile);
                if (lobbies.isEmpty()) {
                    event.reply(t(user.getId(), "Lobby.Browse.None")).setEphemeral(true).queue();
                } else {
                    event.replyEmbeds(browseEmbed(lobbies, 0, user.getId())).setComponents(browseControls(lobbies, profile.gameId(), 0, user.getId()))
                            .setEphemeral(true).queue();
                }
            } else if (id.startsWith("lobbyInviteFriend-")) {
                LobbyInvitation invitation = Main.lobbyService.inviteFriend(user.getId(), suffix(id), value(event, "username"));
                event.reply(t(user.getId(), invitation == null ? "Lobby.InviteFriend.Failed" : "Lobby.InviteFriend.Success"))
                        .setEphemeral(true).queue();
            } else if (id.startsWith("lobbyInviteClan-")) {
                int count = Main.lobbyService.inviteClan(user.getId(), suffix(id), integer(event, "clan"));
                event.reply(t(user.getId(), "Lobby.InviteClan.Sent", Map.of("%count%", String.valueOf(count))))
                        .setEphemeral(true).queue();
            } else if (id.startsWith("lobbySettingsSubmit-")) {
                submitLobbySettings(event, suffix(id), user);
            } else if (id.startsWith("reviewSubmit-")) {
                boolean saved = ReviewRepository.submit(suffix(id), user.getId(), integer(event, "behavior"),
                        integer(event, "teamplay"), integer(event, "reliability"), valueOrEmpty(event, "feedback"));
                if (saved) event.editMessage(t(user.getId(), "Review.Submitted")).setComponents()
                        .queue(hook -> hook.deleteOriginal().queueAfter(5, TimeUnit.SECONDS));
                else event.reply(t(user.getId(), "Review.SubmitFailed")).setEphemeral(true).queue();
            } else if (id.startsWith("matchStats-")) {
                int matchId = suffix(id);
                MatchService.EntrySession session = Main.matchService.getSession(matchId, user.getId());
                if (session == null) {
                    event.reply(t(user.getId(), "Match.SessionUnavailable")).setEphemeral(true).queue();
                } else {
                    List<MatchService.StatTask> batch = Main.matchService.nextBatch(matchId, user.getId());
                    List<String> values = new java.util.ArrayList<>();
                    for (int i = 0; i < batch.size(); i++) values.add(value(event, "v" + i));
                    boolean saved = Main.matchService.submitBatch(matchId, user.getId(), values);
                    LobbyObject matchLobby = LobbyRepository.get(session.lobbyId());
                    boolean closedEntry = matchLobby != null && matchLobby.getStatus() == LobbyStatus.CLOSED;
                    if (!saved) {
                        event.reply(t(user.getId(), "Match.InvalidStat")).setEphemeral(true).queue();
                    } else if (session.complete()) {
                        if (closedEntry) event.editMessageEmbeds(Main.matchService.closureSummary(
                                        session.lobbyId(), user.getId())).setContent(null)
                                .setComponents(Main.matchService.hostEntryComponents(session.lobbyId(), user.getId())).queue();
                        else event.editMessageEmbeds(Main.matchService.activeEntryEmbed(session.lobbyId(), user.getId(),
                                        t(user.getId(), "Match.Submitted"))).setContent(null)
                                .setComponents(Main.matchService.activeEntryComponents(session.lobbyId(), user.getId())).queue();
                    } else {
                        if (closedEntry) event.editMessageEmbeds(Main.matchService.closureSummary(
                                        session.lobbyId(), user.getId()), Main.matchService.entryProgressEmbed(
                                        user.getId(), t(user.getId(), "Match.StatsSaved"))).setContent(null)
                                .setComponents(ActionRow.of(Button.primary("matchNext-" + matchId,
                                        t(user.getId(), "Match.Button.NextStats")))).queue();
                        else event.editMessageEmbeds(Main.matchService.activeEntryEmbed(session.lobbyId(), user.getId(),
                                        t(user.getId(), "Match.StatsSaved"))).setContent(null)
                                .setComponents(Main.matchService.activeEntryComponents(session.lobbyId(), user.getId(), matchId,
                                        t(user.getId(), "Match.Button.NextStats"))).queue();
                    }
                }
            } else if (id.startsWith("matchCorrectionStats-")) {
                int matchId = suffix(id);
                MatchService.CorrectionSession session = Main.matchService.getCorrection(matchId, user.getId());
                if (session == null) {
                    event.reply(t(user.getId(), "Match.SessionUnavailable")).setEphemeral(true).queue();
                } else {
                    List<MatchService.CorrectionTask> batch = Main.matchService.nextCorrectionBatch(matchId, user.getId());
                    List<String> values = new java.util.ArrayList<>();
                    for (int i = 0; i < batch.size(); i++) values.add(value(event, "v" + i));
                    boolean saved = Main.matchService.submitCorrectionBatch(matchId, user.getId(), values);
                    if (!saved) event.reply(t(user.getId(), "Match.InvalidStat")).setEphemeral(true).queue();
                    else if (session.complete()) event.reply(t(user.getId(), "Match.Correction.Submitted"))
                            .setEphemeral(true).queue();
                    else event.reply(t(user.getId(), "Match.Correction.Saved"))
                            .setComponents(ActionRow.of(Button.primary("matchCorrectionNext-" + matchId,
                                    t(user.getId(), "Match.Correction.Next")))).setEphemeral(true).queue();
                }
            }
        } catch (IllegalArgumentException exception) {
            event.reply(t(user.getId(), "General.InvalidInput", Map.of("%error%", exception.getMessage()))).setEphemeral(true).queue();
        }
    }

    private void togglePassiveQueue(ButtonInteractionEvent event, int lobbyId, UserObject user) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null || lobby.getLeaderID() != user.getId()) {
            event.reply(t(user.getId(), "Lobby.OnlyHostConfigure")).setEphemeral(true).queue(); return;
        }
        if (lobby.isPassiveQueue()) {
            Main.lobbyService.deactivatePassiveQueue(user.getId(), lobbyId);
            DiscordLogService.action("LOBBY_PASSIVEQ", "User " + user.getUsername() + " (#" + user.getId()
                    + ") hat PassiveQ für Lobby #" + lobbyId + " deaktiviert");
            event.reply(t(user.getId(), "Lobby.PassiveQueue.Stopped")).setEphemeral(true).queue(ignored ->
                    new UserControlManager(event.getMessage(), user).loadLobbyPage(LobbyRepository.get(lobbyId)));
            return;
        }
        var result = Main.lobbyService.activatePassiveQueue(user.getId(), lobbyId);
        if (result == null) {
            event.reply(t(user.getId(), "Lobby.PassiveQueue.ActionFailed")).setEphemeral(true).queue();
            return;
        }
        DiscordLogService.action("LOBBY_PASSIVEQ", "User " + user.getUsername() + " (#" + user.getId()
                + ") hat PassiveQ für Lobby #" + lobbyId + " aktiviert · Einladungen: "
                + result.invitationsSent());
        String started = t(user.getId(), "Lobby.PassiveQueue.Started",
                Map.of("%count%", String.valueOf(result.invitationsSent())));
        event.reply(started).setEphemeral(true).queue(ignored ->
                new UserControlManager(event.getMessage(), user).loadLobbyPage(LobbyRepository.get(lobbyId)));
    }

    private void showLobbySettingsModal(ButtonInteractionEvent event, int lobbyId, UserObject user) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null || lobby.getLeaderID() != user.getId() || !lobby.isOpen()) {
            event.reply(t(user.getId(), "Lobby.Settings.Result.UNAVAILABLE")).setEphemeral(true).queue();
            return;
        }
        Modal.Builder modal = Modal.create("lobbySettingsSubmit-" + lobbyId,
                t(user.getId(), "Lobby.Settings.Modal.Title"));
        modal.addComponents(Label.of(t(user.getId(), "Lobby.Settings.Modal.Capacity"),
                TextInput.create("capacity", TextInputStyle.SHORT)
                        .setValue(String.valueOf(lobby.getMaxPlayers())).setRequired(true).setMaxLength(2).build()));
        List<GameOption> allowed = Main.lobbyService.compatibleRanksForCurrentMembers(lobby);
        if (!allowed.isEmpty()) {
            String allowedLabel = allowed.getFirst().name() + " – " + allowed.getLast().name();
            modal.addComponents(Label.of(trim(t(user.getId(), "Lobby.Settings.Modal.Minimum"), 45),
                            optionalRankInput("rankMin", lobby.getCustomRankMin(), lobby.getGameID(), allowedLabel)),
                    Label.of(trim(t(user.getId(), "Lobby.Settings.Modal.Maximum"), 45),
                            optionalRankInput("rankMax", lobby.getCustomRankMax(), lobby.getGameID(), allowedLabel)));
        }
        event.replyModal(modal.build()).queue();
    }

    private void submitLobbySettings(ModalInteractionEvent event, int lobbyId, UserObject user) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null || lobby.getLeaderID() != user.getId() || !lobby.isOpen()) {
            event.reply(t(user.getId(), "Lobby.Settings.Result.UNAVAILABLE")).setEphemeral(true).queue();
            return;
        }
        int capacity = integer(event, "capacity");
        Integer rankMin;
        Integer rankMax;
        try {
            rankMin = resolveRankOrder(lobby, valueOrEmpty(event, "rankMin"));
            rankMax = resolveRankOrder(lobby, valueOrEmpty(event, "rankMax"));
        } catch (IllegalArgumentException exception) {
            event.reply(t(user.getId(), "Lobby.Settings.UnknownRank",
                    Map.of("%rank%", exception.getMessage()))).setEphemeral(true).queue();
            return;
        }
        Integer unrestrictedSize = RankCompatibilityRepository.getUnrestrictedPartySize(lobby.getGameID());
        if (LobbyCapacityRules.offersUnrestrictedRankChoice(capacity, unrestrictedSize)
                && !GameOptionRepository.get(lobby.getGameID(), GameOption.Type.RANK).isEmpty()) {
            String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            pendingLobbySettings.put(token, new PendingLobbySettings(user.getId(), lobbyId, capacity,
                    rankMin, rankMax, System.currentTimeMillis()));
            event.reply(t(user.getId(), "Lobby.Settings.Fullstack.Description"))
                    .setComponents(ActionRow.of(
                            Button.success("lobbySettingsRankRule-" + token + "-regular",
                                    t(user.getId(), "Lobby.Wizard.Fullstack.Regular")),
                            Button.secondary("lobbySettingsRankRule-" + token + "-unrestricted",
                                    t(user.getId(), "Lobby.Wizard.Fullstack.Unrestricted"))))
                    .setEphemeral(true).queue();
            return;
        }
        LobbyService.LobbySettingsUpdate result = Main.lobbyService.updateSettings(user.getId(), lobbyId,
                capacity, rankMin, rankMax, false);
        event.reply(settingsResultMessage(user.getId(), result)).setEphemeral(true).queue();
    }

    private void applyPendingLobbySettings(ButtonInteractionEvent event, String componentId, UserObject user) {
        String[] parts = componentId.split("-");
        if (parts.length != 3) return;
        PendingLobbySettings pending = pendingLobbySettings.remove(parts[1]);
        if (pending == null || pending.userId() != user.getId()
                || System.currentTimeMillis() - pending.createdAt() > TimeUnit.MINUTES.toMillis(10)) {
            event.editMessage(t(user.getId(), "Lobby.Settings.Expired")).setComponents().queue();
            return;
        }
        boolean unrestricted = parts[2].equals("unrestricted");
        LobbyService.LobbySettingsUpdate result = Main.lobbyService.updateSettings(user.getId(), pending.lobbyId(),
                pending.capacity(), pending.rankMin(), pending.rankMax(), unrestricted);
        event.editMessage(settingsResultMessage(user.getId(), result)).setComponents().queue();
    }

    private static TextInput optionalRankInput(String id, Integer value, int gameId, String allowedLabel) {
        TextInput.Builder input = TextInput.create(id, TextInputStyle.SHORT)
                .setPlaceholder(trim(allowedLabel, 100)).setRequired(false).setMaxLength(100);
        if (value != null) input.setValue(rankName(gameId, value));
        return input.build();
    }

    private static Integer resolveRankOrder(LobbyObject lobby, String input) {
        if (input == null || input.isBlank()) return null;
        List<GameOption> ranks = GameOptionRepository.get(lobby.getGameID(), GameOption.Type.RANK);
        for (GameOption rank : ranks) if (rank.name().equalsIgnoreCase(input.trim())) return rank.sortOrder();
        try {
            int order = Integer.parseInt(input.trim());
            for (GameOption rank : ranks) if (rank.sortOrder() == order) return order;
        } catch (NumberFormatException ignored) {
            // Rank names are the primary input format.
        }
        throw new IllegalArgumentException(input);
    }

    private static String settingsResultMessage(int userId, LobbyService.LobbySettingsUpdate result) {
        if (result.status() == LobbyService.LobbySettingsStatus.UPDATED && result.lobby() != null) {
            String ranks;
            if (result.lobby().isRankRulesUnrestricted()) ranks = t(userId, "Lobby.View.AnyRank");
            else {
                List<GameOption> allowed = Main.lobbyService.compatibleRanksForCurrentMembers(result.lobby());
                int min = result.effectiveRankMin() == null && !allowed.isEmpty()
                        ? allowed.getFirst().sortOrder() : result.effectiveRankMin() == null ? 0 : result.effectiveRankMin();
                int max = result.effectiveRankMax() == null && !allowed.isEmpty()
                        ? allowed.getLast().sortOrder() : result.effectiveRankMax() == null ? 0 : result.effectiveRankMax();
                ranks = allowed.isEmpty() ? "-" : rankName(result.lobby().getGameID(), min)
                        + " – " + rankName(result.lobby().getGameID(), max);
            }
            return t(userId, "Lobby.Settings.Result.UPDATED", Map.of(
                    "%capacity%", String.valueOf(result.lobby().getMaxPlayers()), "%ranks%", ranks));
        }
        Map<String, String> replacements = Map.of(
                "%members%", String.valueOf(result.lobby() == null ? 0 : LobbyRepository.memberCount(result.lobby().getId())),
                "%min%", result.effectiveRankMin() == null || result.lobby() == null ? "-"
                        : rankName(result.lobby().getGameID(), result.effectiveRankMin()),
                "%max%", result.effectiveRankMax() == null || result.lobby() == null ? "-"
                        : rankName(result.lobby().getGameID(), result.effectiveRankMax()));
        return t(userId, "Lobby.Settings.Result." + result.status().name(), replacements);
    }

    private static String rankName(int gameId, int order) {
        return GameOptionRepository.get(gameId, GameOption.Type.RANK).stream()
                .filter(rank -> rank.sortOrder() == order).map(GameOption::name).findFirst()
                .orElse(String.valueOf(order));
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private record PendingLobbySettings(int userId, int lobbyId, int capacity,
                                        Integer rankMin, Integer rankMax, long createdAt) {}

    private static void showMergeCandidates(ButtonInteractionEvent event, int sourceLobbyId, UserObject user) {
        List<LobbyObject> candidates = Main.lobbyService.findMergeCandidates(sourceLobbyId, user.getId());
        if (candidates.isEmpty()) {
            event.reply(t(user.getId(), "Lobby.Merge.None")).setEphemeral(true).queue();
            return;
        }
        List<SelectOption> options = candidates.stream().limit(25).map(candidate -> {
            UserObject host = UserController.get(candidate.getLeaderID());
            int players = LobbyRepository.memberCount(candidate.getId());
            return SelectOption.of(t(user.getId(), "Lobby.Merge.OptionLabel", Map.of(
                            "%lobbyId%", String.valueOf(candidate.getId()),
                            "%host%", host == null ? "-" : host.getUsername())), String.valueOf(candidate.getId()))
                    .withDescription(trim(t(user.getId(), "Lobby.Merge.OptionDescription", Map.of(
                            "%players%", String.valueOf(players),
                            "%capacity%", String.valueOf(candidate.getMaxPlayers()),
                            "%platform%", candidate.getPlatform(), "%region%", candidate.getRegion())), 100));
        }).toList();
        LobbyObject source = LobbyRepository.get(sourceLobbyId);
        int sourcePlayers = source == null ? 0 : LobbyRepository.memberCount(sourceLobbyId);
        event.editMessageEmbeds(new EmbedCreator().setTitle(t(user.getId(), "Lobby.Merge.Title"))
                        .setDescription(t(user.getId(), "Lobby.Merge.Description", Map.of(
                                "%count%", String.valueOf(candidates.size())))).build())
                .setComponents(
                        ActionRow.of(StringSelectMenu.create("lobbyMergeSelect-" + sourceLobbyId)
                                .setPlaceholder(t(user.getId(), "Lobby.Merge.Select")).addOptions(options).build()),
                        ActionRow.of(
                                Button.success("lobbyStartCurrent-" + sourceLobbyId,
                                        t(user.getId(), "Lobby.PassiveQueue.StartCurrent")).withDisabled(sourcePlayers < 2),
                                Button.danger("lobbyDissolve-" + sourceLobbyId,
                                        t(user.getId(), "Lobby.PassiveQueue.Dissolve")),
                                Button.danger("delete", t(user.getId(), "General.Button.DeleteMessage"))))
                .queue();
    }

    private static void showClanPicker(ButtonInteractionEvent event, UserObject user, int lobbyId,
                                       int requestedPage, boolean edit) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null || lobby.getLeaderID() != user.getId() || !lobby.isOpen()) {
            event.reply(t(user.getId(), "Lobby.InviteClan.Unavailable")).setEphemeral(true).queue();
            return;
        }
        List<ClanRepository.ClanInfo> clans = ClanRepository.inviteableForGame(user.getId(), lobby.getGameID());
        if (clans.isEmpty()) {
            if (edit) event.editMessage(t(user.getId(), "Lobby.InviteClan.None")).setEmbeds().setComponents().queue();
            else event.reply(t(user.getId(), "Lobby.InviteClan.None")).setEphemeral(true).queue();
            return;
        }
        int pages = Math.max(1, (clans.size() + 22) / 23);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int from = page * 23;
        int to = Math.min(from + 23, clans.size());
        List<SelectOption> options = clans.subList(from, to).stream().map(clan ->
                SelectOption.of(clan.name(), String.valueOf(clan.id()))
                        .withDescription(t(user.getId(), "Lobby.InviteClan.Option", Map.of(
                                "%role%", t(user.getId(), "UserProfile.Crews.Role." + clan.role().name()),
                                "%members%", String.valueOf(clan.memberCount()))))).toList();
        List<ActionRow> rows = new java.util.ArrayList<>();
        rows.add(ActionRow.of(StringSelectMenu.create("lobbyClanSelect-" + lobbyId + "-" + page)
                .setPlaceholder(t(user.getId(), "Lobby.InviteClan.Select")).addOptions(options).build()));
        if (pages > 1) rows.add(ActionRow.of(
                Button.secondary("lobbyClanPage-" + lobbyId + "-" + Math.max(0, page - 1),
                        t(user.getId(), "General.Previous")).withDisabled(page == 0),
                Button.secondary("lobbyClanPage-" + lobbyId + "-" + Math.min(pages - 1, page + 1),
                        t(user.getId(), "General.Next")).withDisabled(page >= pages - 1)));
        var embed = new EmbedCreator().setTitle(t(user.getId(), "Lobby.InviteClan.Title"))
                .setDescription(t(user.getId(), "Lobby.InviteClan.Description", Map.of(
                        "%page%", String.valueOf(page + 1), "%pages%", String.valueOf(pages)))).build();
        if (edit) event.editMessageEmbeds(embed).setComponents(rows).queue();
        else event.replyEmbeds(embed).setComponents(rows).setEphemeral(true).queue();
    }

    private static Label input(String label, String id, String placeholder, boolean required) {
        return Label.of(label, TextInput.create(id, TextInputStyle.SHORT).setPlaceholder(placeholder)
                .setRequired(required).setMaxLength(80).build());
    }

    private static ActionRow matchContinueControls(int lobbyId, int userId) {
        return ActionRow.of(Button.primary("matchAdd-" + lobbyId, t(userId, "Match.Button.AddAnother")),
                Button.success("matchDone-" + lobbyId, t(userId, "Match.Button.Done")));
    }

    private static boolean canManageActiveMatches(LobbyObject lobby, int userId) {
        return lobby != null && lobby.getLeaderID() == userId
                && List.of(LobbyStatus.FORMING, LobbyStatus.READY, LobbyStatus.ACTIVE).contains(lobby.getStatus());
    }

    private static Modal correctionModal(int matchId, int userId) {
        List<MatchService.CorrectionTask> tasks = Main.matchService.nextCorrectionBatch(matchId, userId);
        Modal.Builder modal = Modal.create("matchCorrectionStats-" + matchId,
                t(userId, "Match.Correction.Title"));
        for (int i = 0; i < tasks.size(); i++) {
            MatchService.CorrectionTask task = tasks.get(i);
            String label = task.label().length() > 45 ? task.label().substring(0, 45) : task.label();
            TextInputStyle style = "TEXT".equals(task.value().valueType())
                    ? TextInputStyle.PARAGRAPH : TextInputStyle.SHORT;
            modal.addComponents(Label.of(label, TextInput.create("v" + i, style)
                    .setValue(task.value().value()).setRequired(true).setMaxLength(1500).build()));
        }
        return modal.build();
    }

    private static void replyJoin(ButtonInteractionEvent event, LobbyJoinResult result, int lobbyId, int userId) {
        var reply = event.reply(t(userId, "Lobby.JoinResult." + result.name())).setEphemeral(true);
        if (result == LobbyJoinResult.LANGUAGE_MISMATCH && lobbyId > 0) {
            List<SelectOption> options = LobbyLanguageRepository.get(lobbyId).stream().limit(25)
                    .map(language -> SelectOption.of(CommunicationLanguageNames.displayName(language,
                            UserController.get(userId).getLanguage()), language)).toList();
            if (!options.isEmpty()) reply = reply.setComponents(ActionRow.of(
                    StringSelectMenu.create("lobbyAddLanguageJoin-" + lobbyId)
                            .setPlaceholder(t(userId, "Lobby.LanguageMismatch.Select")).addOptions(options).build()));
        }
        reply.queue();
    }

    private static net.dv8tion.jda.api.entities.MessageEmbed browseEmbed(List<LobbyObject> lobbies, int page, int userId) {
        int from = Math.min(page * 5, lobbies.size());
        int to = Math.min(from + 5, lobbies.size());
        String lines = lobbies.subList(from, to).stream().map(lobby -> t(userId, "Lobby.Browse.Entry", Map.of(
                "%lobbyId%", String.valueOf(lobby.getId()), "%players%", String.valueOf(LobbyRepository.memberCount(lobby.getId())),
                "%capacity%", String.valueOf(lobby.getMaxPlayers()), "%rankMin%", String.valueOf(lobby.getRankMin()),
                "%rankMax%", String.valueOf(lobby.getRankMax())))).reduce((a, b) -> a + "\n" + b).orElse("-");
        int pages = Math.max(1, (lobbies.size() + 4) / 5);
        return new EmbedCreator().setTitle(t(userId, "Lobby.Browse.Title", Map.of("%page%", String.valueOf(page + 1),
                "%pages%", String.valueOf(pages)))).setDescription(lines).build();
    }

    private static List<ActionRow> browseControls(List<LobbyObject> lobbies, int gameId, int page, int userId) {
        int from = Math.min(page * 5, lobbies.size());
        int to = Math.min(from + 5, lobbies.size());
        List<ActionRow> rows = new java.util.ArrayList<>();
        List<Button> joinButtons = lobbies.subList(from, to).stream()
                .map(lobby -> Button.success("lobbyJoin-" + lobby.getId(), t(userId, "Lobby.Browse.Join",
                        Map.of("%lobbyId%", String.valueOf(lobby.getId()))))).toList();
        if (!joinButtons.isEmpty()) rows.add(ActionRow.of(joinButtons));
        int pages = Math.max(1, (lobbies.size() + 4) / 5);
        if (pages > 1) rows.add(ActionRow.of(
                Button.secondary("browsePage-" + gameId + "-" + Math.max(0, page - 1), t(userId, "General.Previous")).withDisabled(page == 0),
                Button.secondary("browsePage-" + gameId + "-" + Math.min(pages - 1, page + 1), t(userId, "General.Next")).withDisabled(page >= pages - 1)));
        return rows;
    }

    private static int suffix(String id) { return Integer.parseInt(id.substring(id.lastIndexOf('-') + 1)); }
    private static int integer(ModalInteractionEvent event, String id) {
        try { return Integer.parseInt(value(event, id)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(id + " must be a number"); }
    }
    private static String value(ModalInteractionEvent event, String id) {
        if (event.getValue(id) == null) throw new IllegalArgumentException(id + " is required");
        return event.getValue(id).getAsString().trim();
    }
    private static String valueOrEmpty(ModalInteractionEvent event, String id) {
        return event.getValue(id) == null ? "" : event.getValue(id).getAsString().trim();
    }
    private static String t(int userId, String key) { return LanguageManager.getMessageForUser(key, userId); }
    private static String t(int userId, String key, Map<String, String> replacements) {
        return LanguageManager.getMessageForUser(key, userId, replacements);
    }
}
