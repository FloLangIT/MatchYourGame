package de.flolang.matchyourgame.manager.lobby;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.game.*;
import de.flolang.matchyourgame.database.lobby.LobbyObject;
import de.flolang.matchyourgame.database.lobby.LobbyRepository;
import de.flolang.matchyourgame.database.lobby.LobbyLanguageRepository;
import de.flolang.matchyourgame.database.lobby.SearchProfile;
import de.flolang.matchyourgame.database.lobby.SearchProfileRepository;
import de.flolang.matchyourgame.database.profile.GameProfileRepository;
import de.flolang.matchyourgame.database.profile.GameProfile;
import de.flolang.matchyourgame.database.profile.CommunicationLanguage;
import de.flolang.matchyourgame.database.profile.CommunicationLanguageRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.party.PartyObject;
import de.flolang.matchyourgame.database.party.PartyRepository;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.language.Language;
import de.flolang.matchyourgame.language.CommunicationLanguageNames;
import de.flolang.matchyourgame.manager.UserControlManager;
import de.flolang.matchyourgame.manager.ManagementMessageUpdater;
import de.flolang.matchyourgame.manager.TutorialManager;
import de.flolang.matchyourgame.logging.DiscordLogService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class GameSelectionWizard extends ListenerAdapter {
    public enum Flow { LOBBY, PASSIVE_QUEUE, PROFILE_UPDATE }
    private enum Step { GAME, MODE, PLATFORM, REGION, RANK, ROLE }
    private static final Duration SESSION_LIFETIME = Duration.ofMinutes(15);
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public void start(ButtonInteractionEvent event, UserObject user, Flow flow) {
        cleanup();
        PartyObject party = flow == Flow.LOBBY ? PartyRepository.getForUser(user.getId()) : null;
        if (party != null && party.hostUserId() != user.getId()) {
            event.reply(t(user.getId(), "Lobby.Wizard.PartyOnlyHost")).setEphemeral(true).queue();
            return;
        }
        String token = UUID.randomUUID().toString().substring(0, 8);
        Session session = new Session(token, user.getId(), flow, Instant.now());
        sessions.put(token, session);
        renderReply(event, session, Step.GAME, 0);
    }

    public void startProfileUpdate(ButtonInteractionEvent event, UserObject user, int gameId) {
        cleanup();
        String token = UUID.randomUUID().toString().substring(0, 8);
        Session session = new Session(token, user.getId(), Flow.PROFILE_UPDATE, Instant.now());
        session.autoDeleteOnComplete = true;
        session.modeId = gameId;
        GameObject mode = GameRepository.get(gameId);
        session.mainGameId = mode != null && mode.getSubGameFrom() != null ? mode.getSubGameFrom().getId() : gameId;
        sessions.put(token, session);
        List<GameProfile> profiles = GameProfileRepository.getForGame(user.getId(), gameId);
        if (profiles.size() == 1) {
            if (!initializeExistingProfile(session, profiles.getFirst())) {
                sessions.remove(token);
                event.reply(t(user.getId(), "GameProfile.Manage.ProfileUnavailable")).setEphemeral(true).queue();
                return;
            }
            renderExistingReply(event, session);
        } else if (profiles.size() > 1) {
            session.profilePlatformSelection = true;
            renderReply(event, session, Step.PLATFORM, 0);
        } else {
            renderReply(event, session, Step.PLATFORM, 0);
        }
    }

    public void startTutorialProfile(ButtonInteractionEvent event, UserObject user, int gameId) {
        cleanup();
        GameObject mode = GameRepository.get(gameId);
        if (mode == null || !mode.isActive()) {
            event.reply(t(user.getId(), "Lobby.Wizard.InvalidSelection")).setEphemeral(true).queue();
            return;
        }
        String token = UUID.randomUUID().toString().substring(0, 8);
        Session session = new Session(token, user.getId(), Flow.PROFILE_UPDATE, Instant.now());
        session.tutorial = true;
        session.modeId = gameId;
        session.mainGameId = mode.getSubGameFrom() == null ? gameId : mode.getSubGameFrom().getId();
        sessions.put(token, session);
        GameProfile existing = GameProfileRepository.get(user.getId(), gameId);
        if (existing != null && initializeExistingProfile(session, existing)) {
            renderExistingReply(event, session);
            return;
        }
        if (options(session, GameOption.Type.PLATFORM).isEmpty()) {
            sessions.remove(token);
            event.reply(t(user.getId(), "Lobby.Wizard.NoPlatforms")).setEphemeral(true).queue();
            return;
        }
        renderReply(event, session, Step.PLATFORM, 0);
    }

    public void startExistingProfileUpdate(StringSelectInteractionEvent event, UserObject user,
                                           int gameId, String platform) {
        cleanup();
        GameProfile profile = GameProfileRepository.get(user.getId(), gameId, platform);
        if (profile == null) {
            event.reply(t(user.getId(), "GameProfile.Manage.ProfileUnavailable")).setEphemeral(true).queue();
            return;
        }
        String token = UUID.randomUUID().toString().substring(0, 8);
        Session session = new Session(token, user.getId(), Flow.PROFILE_UPDATE, Instant.now());
        session.modeId = gameId;
        GameObject mode = GameRepository.get(gameId);
        session.mainGameId = mode != null && mode.getSubGameFrom() != null ? mode.getSubGameFrom().getId() : gameId;
        if (!initializeExistingProfile(session, profile)) {
            event.reply(t(user.getId(), "GameProfile.Manage.ProfileUnavailable")).setEphemeral(true).queue();
            return;
        }
        sessions.put(token, session);
        renderExistingEdit(event, session);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String customId = event.getComponentId();
        if (customId.startsWith("gameProfileDeleteAsk-")
                || customId.startsWith("gameProfileDeleteConfirm-")
                || customId.startsWith("gameProfileDeleteCancel-")) {
            handleProfileDeletion(event, customId);
            return;
        }
        if (customId.startsWith("gameWizardConfirm-") || customId.startsWith("gameWizardCancel-")) {
            handleProfileConfirmation(event, customId);
            return;
        }
        if (customId.startsWith("lobbyLanguagesPage-") || customId.startsWith("lobbyLanguagesDone-")) {
            String[] parts = customId.split("-");
            UserObject user = UserController.get(event.getUser().getIdLong());
            Session session = parts.length >= 2 ? sessions.get(parts[1]) : null;
            if (user == null || session == null || session.userId != user.getId() || expired(session)) {
                event.reply(user == null ? "Session expired" : t(user.getId(), "Lobby.Wizard.Expired"))
                        .setEphemeral(true).queue();
                return;
            }
            if (customId.startsWith("lobbyLanguagesDone-")) {
                if (session.languages.isEmpty()) {
                    event.reply(t(user.getId(), "Lobby.Wizard.NoCommunicationLanguages"))
                            .setEphemeral(true).queue();
                } else event.replyModal(capacityModal(session)).queue();
            } else {
                int page;
                try { page = Integer.parseInt(parts[2]); }
                catch (NumberFormatException exception) { page = 0; }
                renderLobbyLanguages(event, session, page);
            }
            return;
        }
        if (!customId.startsWith("lobbyRankRule-")) return;
        String[] parts = customId.split("-");
        UserObject user = UserController.get(event.getUser().getIdLong());
        Session session = parts.length == 3 ? sessions.get(parts[1]) : null;
        if (user == null || session == null || session.userId != user.getId() || expired(session)) {
            event.editMessageEmbeds(new EmbedCreator().setDescription(user == null
                            ? LanguageManager.getMessageByLanguage("Lobby.Wizard.LobbyExpired", Language.EN)
                            : t(user.getId(), "Lobby.Wizard.LobbyExpired")).build())
                    .setComponents().queue();
            if (session != null) sessions.remove(session.token);
            return;
        }
        boolean unrestrictedRanks = parts[2].equals("unrestricted");
        sessions.remove(session.token);
        if (!unrestrictedRanks && !partyRanksCompatible(session)) {
            event.editMessageEmbeds(new EmbedCreator().setTitle(t(user.getId(), "Lobby.Wizard.Modal.Title"))
                            .setDescription(t(user.getId(), "Lobby.Wizard.PartyRanksIncompatible")).build())
                    .setComponents(ActionRow.of(Button.primary("mainPage",
                            t(user.getId(), "UserProfile.Button.Back")))).queue();
            return;
        }
        LobbyObject lobby = createLobby(session, user, unrestrictedRanks);
        event.deferEdit().queue();
        if (lobby == null) event.getMessage().editMessageEmbeds(new EmbedCreator()
                        .setTitle(t(user.getId(), "Lobby.Wizard.Modal.Title"))
                        .setDescription(t(user.getId(), "Lobby.Wizard.CreateFailed")).build())
                .setComponents(ActionRow.of(Button.primary("mainPage", t(user.getId(), "UserProfile.Button.Back")))).queue();
        else new UserControlManager(event.getMessage(), user).loadLobbyPage(lobby);
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String customId = event.getComponentId();
        if (customId.startsWith("lobbyLanguages-")) {
            String[] parts = customId.split("-");
            String token = parts.length >= 2 ? parts[1] : "";
            int page;
            try { page = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0; }
            catch (NumberFormatException exception) { page = 0; }
            Session session = sessions.get(token);
            UserObject user = UserController.get(event.getUser().getIdLong());
            if (session == null || user == null || session.userId != user.getId() || expired(session)) {
                event.editMessageEmbeds(new EmbedCreator().setDescription(user == null
                                ? LanguageManager.getMessageByLanguage("Lobby.Wizard.Expired", Language.EN)
                                : t(user.getId(), "Lobby.Wizard.Expired")).build()).setComponents().queue();
                return;
            }
            List<CommunicationLanguage> configured = CommunicationLanguageRepository.getForUser(user.getId());
            int from = Math.min(Math.max(0, page) * 25, configured.size());
            int to = Math.min(from + 25, configured.size());
            Set<String> pageCodes = configured.subList(from, to).stream()
                    .map(CommunicationLanguage::code).collect(java.util.stream.Collectors.toSet());
            List<String> selected = new ArrayList<>(session.languages);
            selected.removeIf(pageCodes::contains);
            event.getValues().stream().filter(pageCodes::contains).forEach(selected::add);
            session.languages = configured.stream().map(CommunicationLanguage::code)
                    .filter(selected::contains).toList();
            if (configured.size() <= 25) {
                if (session.languages.isEmpty()) {
                    renderError(event, session, "Lobby.Wizard.NoCommunicationLanguages");
                } else event.replyModal(capacityModal(session)).queue();
            } else {
                renderLobbyLanguages(event, session, page);
            }
            return;
        }
        if (!customId.startsWith("gameWizard-")) return;
        String[] id = customId.split("-");
        if (id.length != 4) return;
        Session session = sessions.get(id[1]);
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) {
            event.editMessageEmbeds(new EmbedCreator().setDescription(
                    LanguageManager.getMessageByLanguage("Lobby.Wizard.Expired", Language.EN)).build()).setComponents().queue();
            return;
        }
        if (session == null || session.userId != user.getId() || expired(session)) {
            event.editMessageEmbeds(new EmbedCreator().setDescription(t(user.getId(), "Lobby.Wizard.Expired")).build())
                    .setComponents(ActionRow.of(Button.primary("mainPage", t(user.getId(), "UserProfile.Button.Back")))).queue();
            if (session != null) sessions.remove(session.token);
            return;
        }
        Step step = Step.valueOf(id[2]);
        int page = Integer.parseInt(id[3]);
        String value = event.getValues().getFirst();
        if (value.equals("nav:previous")) { renderEdit(event, session, step, page - 1); return; }
        if (value.equals("nav:next")) { renderEdit(event, session, step, page + 1); return; }
        int selectedId = Integer.parseInt(value.substring("item:".length()));
        if (choices(session, step).stream().noneMatch(choice -> choice.id == selectedId)) {
            renderError(event, session, "Lobby.Wizard.InvalidSelection"); return;
        }
        handleSelection(event, session, step, selectedId);
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!event.getModalId().startsWith("gameWizardLobby-")) return;
        String token = event.getModalId().substring("gameWizardLobby-".length());
        Session session = sessions.get(token);
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) {
            event.deferEdit().queue();
            event.getMessage().editMessageEmbeds(new EmbedCreator().setDescription(
                    LanguageManager.getMessageByLanguage("Lobby.Wizard.LobbyExpired", Language.EN)).build()).setComponents().queue();
            return;
        }
        if (session == null || session.userId != user.getId() || expired(session)) {
            event.deferEdit().queue();
            event.getMessage().editMessageEmbeds(new EmbedCreator().setDescription(t(user.getId(), "Lobby.Wizard.LobbyExpired")).build())
                    .setComponents(ActionRow.of(Button.primary("mainPage", t(user.getId(), "UserProfile.Button.Back")))).queue(); return;
        }
        try {
            int capacity = Integer.parseInt(event.getValue("capacity").getAsString());
            Integer unrestrictedSize = RankCompatibilityRepository.getUnrestrictedPartySize(session.modeId);
            GameProfile profile = session.selectedProfile;
            if (profile == null) profile = GameProfileRepository.upsert(user.getId(), session.modeId,
                    session.platform.name(), session.region.name(), session.rank == null ? 0 : session.rank.sortOrder(),
                    session.role == null ? "ANY" : session.role.name());
            if (profile == null) throw new IllegalStateException("Game profile could not be saved");
            SearchProfile lobbySearchProfile = SearchProfileRepository.get(user.getId(), session.modeId,
                    profile.platform());
            if (lobbySearchProfile == null && SearchProfileRepository.upsert(user.getId(), session.modeId,
                    profile.platform(), profile.region(), user.getLanguage().name(),
                    profile.rankValue(), profile.preferredRole(), true) == null)
                throw new IllegalStateException("PassiveQ profile could not be enabled");
            session.selectedProfile = profile;
            session.capacity = capacity;
            event.deferEdit().queue();
            PartyObject party = PartyRepository.getForUser(user.getId());
            if (party != null && party.memberIds().size() > capacity) {
                sessions.remove(token);
                renderCreationError(event, user, "Lobby.Wizard.PartyTooLarge", Map.of(
                        "%partySize%", String.valueOf(party.memberIds().size()),
                        "%capacity%", String.valueOf(capacity)));
                return;
            }
            if (party != null) {
                String partyPlatform = profile.platform();
                String missing = party.memberIds().stream()
                        .filter(memberId -> GameProfileRepository.get(memberId, session.modeId, partyPlatform) == null)
                        .map(UserController::get).filter(Objects::nonNull).map(UserObject::getUsername)
                        .reduce((first, next) -> first + ", " + next).orElse("");
                if (!missing.isBlank()) {
                    sessions.remove(token);
                    renderCreationError(event, user, "Lobby.Wizard.PartyProfilesMissing", Map.of("%players%", missing));
                    return;
                }
                if (LobbyLanguageRepository.intersectionForUsers(session.languages, party.memberIds()).isEmpty()) {
                    sessions.remove(token);
                    renderCreationError(event, user, "Lobby.Wizard.PartyNoCommonLanguage", Map.of());
                    return;
                }
            }
            if (LobbyCapacityRules.offersUnrestrictedRankChoice(capacity, unrestrictedSize)) {
                event.getMessage().editMessageEmbeds(new EmbedCreator()
                                .setTitle(t(user.getId(), "Lobby.Wizard.Fullstack.Title"))
                                .setDescription(t(user.getId(), "Lobby.Wizard.Fullstack.Description")).build())
                        .setComponents(ActionRow.of(
                                Button.success("lobbyRankRule-" + token + "-regular",
                                        t(user.getId(), "Lobby.Wizard.Fullstack.Regular")),
                                Button.secondary("lobbyRankRule-" + token + "-unrestricted",
                                        t(user.getId(), "Lobby.Wizard.Fullstack.Unrestricted"))),
                                ActionRow.of(Button.primary("mainPage", t(user.getId(), "UserProfile.Button.Back"))))
                        .queue();
                return;
            }
            if (!partyRanksCompatible(session)) {
                sessions.remove(token);
                renderCreationError(event, user, "Lobby.Wizard.PartyRanksIncompatible", Map.of());
                return;
            }
            sessions.remove(token);
            LobbyObject lobby = createLobby(session, user, false);
            if (lobby == null) event.getMessage().editMessageEmbeds(new EmbedCreator()
                            .setTitle(t(user.getId(), "Lobby.Wizard.Modal.Title"))
                            .setDescription(t(user.getId(), "Lobby.Wizard.CreateFailed")).build())
                    .setComponents(ActionRow.of(Button.primary("mainPage", t(user.getId(), "UserProfile.Button.Back")))).queue();
            else new UserControlManager(event.getMessage(), user).loadLobbyPage(lobby);
        } catch (NumberFormatException exception) {
            sessions.remove(token);
            event.deferEdit().queue();
            event.getMessage().editMessageEmbeds(new EmbedCreator().setTitle(t(user.getId(), "Lobby.Wizard.Modal.Title"))
                            .setDescription(t(user.getId(), "Lobby.Wizard.InvalidCapacity")).build())
                    .setComponents(ActionRow.of(Button.primary("mainPage", t(user.getId(), "UserProfile.Button.Back")))).queue();
        } catch (RuntimeException exception) {
            sessions.remove(token);
            DiscordLogService.error("GameSelectionWizard", "Lobby-Erstellung für User #" + user.getId()
                    + " und Game #" + session.modeId + " fehlgeschlagen", exception.toString());
            if (!event.isAcknowledged()) event.deferEdit().queue();
            renderCreationError(event, user, "Lobby.Wizard.CreateFailed", Map.of());
        }
    }

    private static LobbyObject createLobby(Session session, UserObject user, boolean unrestrictedRanks) {
        GameProfile profile = session.selectedProfile;
        if (profile == null) return null;
        int rank = unrestrictedRanks ? -1 : profile.rankValue();
        LobbyObject lobby = Main.lobbyService.create(user.getId(), session.modeId, session.capacity,
                profile.platform(), profile.region(), user.getLanguage().name(), rank, rank,
                profile.preferredRole());
        if (lobby != null) {
            PartyObject party = PartyRepository.getForUser(user.getId());
            List<String> languages = party == null ? session.languages
                    : LobbyLanguageRepository.intersectionForUsers(session.languages, party.memberIds());
            if (languages.isEmpty()) {
                de.flolang.matchyourgame.database.lobby.LobbyRepository.cancel(lobby.getId());
                return null;
            }
            LobbyLanguageRepository.set(lobby.getId(), languages);
            Main.lobbyService.refreshManagementMessages(lobby.getId());
            Main.lobbyService.prepareVoiceIfFull(lobby.getId());
        }
        return lobby;
    }

    private static void renderCreationError(ModalInteractionEvent event, UserObject user, String key,
                                            Map<String, String> replacements) {
        event.getMessage().editMessageEmbeds(new EmbedCreator()
                        .setTitle(t(user.getId(), "Lobby.Wizard.Modal.Title"))
                        .setDescription(t(user.getId(), key, replacements)).build())
                .setComponents(ActionRow.of(Button.primary("mainPage",
                        t(user.getId(), "UserProfile.Button.Back")))).queue();
    }

    private static boolean partyRanksCompatible(Session session) {
        GameObject mode = GameRepository.get(session.modeId);
        PartyObject party = PartyRepository.getForUser(session.userId);
        if (party == null || mode == null || !mode.isSkillbased()) return true;
        String platform = session.selectedProfile == null ? null : session.selectedProfile.platform();
        if (platform == null) return false;
        List<Integer> ranks = new ArrayList<>();
        for (int memberId : party.memberIds()) {
            GameProfile profile = GameProfileRepository.get(memberId, session.modeId, platform);
            if (profile == null) return false;
            ranks.add(profile.rankValue());
        }
        for (int source : ranks)
            for (int target : ranks)
                if (!RankCompatibilityRepository.isCompatible(session.modeId, source, target)) return false;
        return true;
    }

    private void handleSelection(StringSelectInteractionEvent event, Session session, Step step, int selectedId) {
        switch (step) {
            case GAME -> {
                session.mainGameId = selectedId;
                List<GameObject> modes = modes(selectedId);
                if (modes.isEmpty()) {
                    renderError(event, session, "Lobby.Wizard.NoModes");
                    return;
                }
                renderEdit(event, session, Step.MODE, 0);
            }
            case MODE -> {
                session.modeId = selectedId;
                if (session.flow == Flow.LOBBY) {
                    continueLobbyWithProfile(event, session);
                    return;
                }
                if (options(session, GameOption.Type.PLATFORM).isEmpty()) {
                    renderError(event, session, "Lobby.Wizard.NoPlatforms"); return;
                }
                renderEdit(event, session, Step.PLATFORM, 0);
            }
            case PLATFORM -> {
                if (session.profilePlatformSelection) {
                    List<GameProfile> profiles = GameProfileRepository.getForGame(session.userId, session.modeId);
                    if (selectedId < 0 || selectedId >= profiles.size()) {
                        renderError(event, session, "Lobby.Wizard.InvalidSelection");
                        return;
                    }
                    GameProfile profile = profiles.get(selectedId);
                    if (session.flow == Flow.LOBBY) {
                        session.selectedProfile = profile;
                        finish(event, session);
                    } else if (!initializeExistingProfile(session, profile)) {
                        renderError(event, session, "GameProfile.Manage.ProfileUnavailable");
                    } else {
                        renderExistingEdit(event, session);
                    }
                    return;
                }
                session.platform = option(session, selectedId, GameOption.Type.PLATFORM);
                session.originalProfile = GameProfileRepository.get(session.userId, session.modeId,
                        session.platform.name());
                if (options(session, GameOption.Type.REGION).isEmpty()) {
                    renderError(event, session, "Lobby.Wizard.NoRegions"); return;
                }
                renderEdit(event, session, Step.REGION, 0);
            }
            case REGION -> {
                session.region = option(session, selectedId, GameOption.Type.REGION);
                GameObject mode = GameRepository.get(session.modeId);
                if (mode != null && mode.isSkillbased()) {
                    if (options(session, GameOption.Type.RANK).isEmpty()) {
                        renderError(event, session, "Lobby.Wizard.NoRanks"); return;
                    }
                    renderEdit(event, session, Step.RANK, 0);
                } else moveToRoleOrFinish(event, session);
            }
            case RANK -> {
                session.rank = option(session, selectedId, GameOption.Type.RANK);
                moveToRoleOrFinish(event, session);
            }
            case ROLE -> {
                session.role = option(session, selectedId, GameOption.Type.ROLE);
                finish(event, session);
            }
        }
    }

    private void continueLobbyWithProfile(StringSelectInteractionEvent event, Session session) {
        List<GameProfile> profiles = GameProfileRepository.getForGame(session.userId, session.modeId);
        if (profiles.size() == 1) {
            session.selectedProfile = profiles.getFirst();
            finish(event, session);
        } else if (profiles.size() > 1) {
            session.profilePlatformSelection = true;
            renderEdit(event, session, Step.PLATFORM, 0);
        } else {
            session.profilePlatformSelection = false;
            session.creatingProfileForLobby = true;
            if (options(session, GameOption.Type.PLATFORM).isEmpty())
                renderError(event, session, "Lobby.Wizard.NoPlatforms");
            else renderEdit(event, session, Step.PLATFORM, 0);
        }
    }

    private void moveToRoleOrFinish(StringSelectInteractionEvent event, Session session) {
        if (options(session, GameOption.Type.ROLE).isEmpty()) finish(event, session);
        else renderEdit(event, session, Step.ROLE, 0);
    }

    private void finish(StringSelectInteractionEvent event, Session session) {
        if (session.flow == Flow.PASSIVE_QUEUE || session.flow == Flow.PROFILE_UPDATE) {
            renderConfirmation(event, session);
        } else {
            List<CommunicationLanguage> languages = CommunicationLanguageRepository.getForUser(session.userId);
            if (languages.size() == 1) {
                session.languages = List.of(languages.getFirst().code());
                event.replyModal(capacityModal(session)).queue();
            } else {
                session.languages = new ArrayList<>();
                renderLobbyLanguages(event, session, 0);
            }
        }
    }

    private static void renderLobbyLanguages(StringSelectInteractionEvent event, Session session, int page) {
        var display = lobbyLanguagePage(session, page);
        event.editMessageEmbeds(display.embed()).setComponents(display.rows()).queue();
    }

    private static void renderLobbyLanguages(ButtonInteractionEvent event, Session session, int page) {
        var display = lobbyLanguagePage(session, page);
        event.editMessageEmbeds(display.embed()).setComponents(display.rows()).queue();
    }

    private static LobbyLanguagePage lobbyLanguagePage(Session session, int requestedPage) {
        List<CommunicationLanguage> languages = CommunicationLanguageRepository.getForUser(session.userId);
        int pages = Math.max(1, (languages.size() + 24) / 25);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int from = Math.min(page * 25, languages.size());
        int to = Math.min(from + 25, languages.size());
        List<CommunicationLanguage> displayed = languages.subList(from, to);
        List<SelectOption> options = displayed.stream().map(language ->
                SelectOption.of(CommunicationLanguageNames.displayNameWithFlag(language.code(),
                                UserController.get(session.userId).getLanguage()) + " · #" + language.priority(),
                        language.code()).withDefault(session.languages.contains(language.code()))).toList();
        List<ActionRow> rows = new ArrayList<>();
        rows.add(ActionRow.of(StringSelectMenu.create("lobbyLanguages-" + session.token + "-" + page)
                .setPlaceholder(t(session.userId, "Lobby.Wizard.Languages.Select"))
                .setMinValues(0).setMaxValues(options.size()).addOptions(options).build()));
        if (pages > 1) rows.add(ActionRow.of(
                Button.secondary("lobbyLanguagesPage-" + session.token + "-" + Math.max(0, page - 1),
                                t(session.userId, "General.Previous")).withDisabled(page == 0),
                Button.secondary("lobbyLanguagesPage-" + session.token + "-"
                                + Math.min(pages - 1, page + 1), t(session.userId, "General.Next"))
                        .withDisabled(page >= pages - 1),
                Button.success("lobbyLanguagesDone-" + session.token,
                        t(session.userId, "Lobby.Wizard.Languages.Done"))));
        rows.add(ActionRow.of(Button.primary("mainPage", t(session.userId, "UserProfile.Button.Back"))));
        return new LobbyLanguagePage(new EmbedCreator()
                .setTitle(t(session.userId, "Lobby.Wizard.Languages.Title"))
                .setDescription(t(session.userId, "Lobby.Wizard.Languages.Description")
                        + "\n\n" + t(session.userId, "GameProfile.Languages.Page", Map.of(
                        "%page%", String.valueOf(page + 1), "%pages%", String.valueOf(pages)))).build(), rows);
    }

    private void handleProfileConfirmation(ButtonInteractionEvent event, String customId) {
        boolean confirm = customId.startsWith("gameWizardConfirm-");
        String token = customId.substring((confirm ? "gameWizardConfirm-" : "gameWizardCancel-").length());
        Session session = sessions.get(token);
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null || session == null || session.userId != user.getId() || expired(session)) {
            event.reply(user == null ? "Session expired" : t(user.getId(), "Lobby.Wizard.Expired"))
                    .setEphemeral(true).queue();
            if (session != null) sessions.remove(token);
            return;
        }
        if (!confirm) {
            sessions.remove(token);
            event.deferEdit().queue();
            if (session.tutorial) TutorialManager.profileCancelled(event.getMessage(), user);
            else if (session.autoDeleteOnComplete) event.getMessage().delete().queue();
            else new UserControlManager(event.getMessage(), user).loadStartPage();
            return;
        }
        boolean saved = saveProfile(session, user);
        if (!saved) {
            event.reply(t(user.getId(), "GameProfile.SaveFailed")).setEphemeral(true).queue();
            return;
        }
        sessions.remove(token);
        String key = session.flow == Flow.PASSIVE_QUEUE
                ? "PassiveQ.Profile.Activated" : session.passiveAutoEnabled
                ? "GameProfile.SavedPassiveEnabled" : "GameProfile.Saved";
        event.reply(t(user.getId(), key, Map.of("%game%", gameDisplayName(session.modeId),
                        "%platform%", session.platform.name())))
                .setEphemeral(true).queue();
        if (session.tutorial)
            TutorialManager.profileConfigured(event.getMessage(), user);
        else if (session.autoDeleteOnComplete)
            event.getMessage().delete().queueAfter(5, TimeUnit.SECONDS);
        else new UserControlManager(event.getMessage(), user).loadStartPage();
    }

    private void handleProfileDeletion(ButtonInteractionEvent event, String customId) {
        String prefix = customId.startsWith("gameProfileDeleteAsk-")
                ? "gameProfileDeleteAsk-" : customId.startsWith("gameProfileDeleteConfirm-")
                ? "gameProfileDeleteConfirm-" : "gameProfileDeleteCancel-";
        String token = customId.substring(prefix.length());
        Session session = sessions.get(token);
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null || session == null || session.userId != user.getId()
                || session.originalProfile == null || session.tutorial || expired(session)) {
            event.reply(user == null ? "Session expired" : t(user.getId(), "Lobby.Wizard.Expired"))
                    .setEphemeral(true).queue();
            if (session != null && expired(session)) sessions.remove(token);
            return;
        }
        if (prefix.equals("gameProfileDeleteCancel-")) {
            renderExistingReply(event, session);
            return;
        }
        if (prefix.equals("gameProfileDeleteAsk-")) {
            Map<String, String> replacements = Map.of(
                    "%game%", gameDisplayName(session.modeId),
                    "%platform%", session.originalProfile.platform());
            event.editMessageEmbeds(new EmbedCreator()
                            .setTitle(t(user.getId(), "GameProfile.Delete.Title"))
                            .setDescription(t(user.getId(), "GameProfile.Delete.Description", replacements)).build())
                    .setComponents(ActionRow.of(
                            Button.danger("gameProfileDeleteConfirm-" + token,
                                    t(user.getId(), "GameProfile.Delete.Confirm")),
                            Button.secondary("gameProfileDeleteCancel-" + token,
                                    t(user.getId(), "GameProfile.Delete.Cancel"))))
                    .queue();
            return;
        }
        if (LobbyRepository.getActiveForUser(user.getId()) != null) {
            event.reply(t(user.getId(), "GameProfile.Delete.ActiveLobby")).setEphemeral(true).queue();
            return;
        }
        GameProfile profile = session.originalProfile;
        boolean deleted = GameProfileRepository.delete(
                user.getId(), session.modeId, profile.platform());
        if (deleted) {
            sessions.remove(token);
            ManagementMessageUpdater.refreshFriendActivity(user.getId());
            DiscordLogService.action("GAME_PROFILE_DELETED", "User #" + user.getId()
                    + " hat Spielprofil für Game #" + session.modeId + " auf Plattform "
                    + profile.platform() + " gelöscht");
        }
        event.reply(t(user.getId(), deleted
                ? "GameProfile.Delete.Success" : "GameProfile.Delete.Failed")).setEphemeral(true).queue();
        if (deleted) new UserControlManager(event.getMessage(), user).loadGameProfilesPage();
    }

    private static boolean saveProfile(Session session, UserObject user) {
        int rank = session.rank == null ? 0 : session.rank.sortOrder();
        String role = session.role == null ? "ANY" : session.role.name();
        if (session.flow == Flow.PASSIVE_QUEUE) {
            SearchProfile profile = SearchProfileRepository.upsert(session.userId, session.modeId,
                    session.platform.name(), session.region.name(), user.getLanguage().name(), rank, role, true);
            if (profile != null) {
                ManagementMessageUpdater.refreshFriendActivity(session.userId);
                DiscordLogService.action("PASSIVEQ_GAME", "User #" + session.userId
                        + " hat PassiveQ für Game #" + session.modeId + " auf Plattform "
                        + session.platform.name() + " aktiviert/aktualisiert");
            }
            return profile != null;
        }
        GameProfile saved = GameProfileRepository.upsert(session.userId, session.modeId,
                session.platform.name(), session.region.name(), rank, role);
        if (saved == null) return false;
        DiscordLogService.action("GAME_PROFILE", "User #" + session.userId + " hat Spielprofil für Game #"
                + session.modeId + " auf Plattform " + session.platform.name() + " gespeichert · Region "
                + session.region.name() + " · Rang " + rank + " · Rolle " + role);
        SearchProfile existing = SearchProfileRepository.get(session.userId, session.modeId, session.platform.name());
        if (existing != null) {
            if (SearchProfileRepository.upsert(session.userId, session.modeId,
                    session.platform.name(), session.region.name(), user.getLanguage().name(), rank, role,
                    existing.passiveEnabled()) == null) return false;
        } else if (session.originalProfile == null) {
            if (SearchProfileRepository.upsert(session.userId, session.modeId,
                    session.platform.name(), session.region.name(), user.getLanguage().name(), rank, role,
                    true) == null) return false;
            session.passiveAutoEnabled = true;
            ManagementMessageUpdater.refreshFriendActivity(session.userId);
            DiscordLogService.action("PASSIVEQ_GAME", "User #" + session.userId
                    + " hat durch ein neues Spielprofil PassiveQ für Game #" + session.modeId
                    + " auf Plattform " + session.platform.name() + " aktiviert");
        }
        return true;
    }

    private static boolean initializeExistingProfile(Session session, GameProfile profile) {
        session.originalProfile = profile;
        session.platform = options(session, GameOption.Type.PLATFORM).stream()
                .filter(option -> option.name().equalsIgnoreCase(profile.platform())).findFirst().orElse(null);
        session.region = options(session, GameOption.Type.REGION).stream()
                .filter(option -> option.name().equalsIgnoreCase(profile.region())).findFirst().orElse(null);
        GameObject mode = GameRepository.get(session.modeId);
        if (session.platform == null || session.region == null) return false;
        if (mode != null && mode.isSkillbased()) {
            session.rank = options(session, GameOption.Type.RANK).stream()
                    .filter(option -> option.sortOrder() == profile.rankValue()).findFirst().orElse(null);
            if (session.rank == null) return false;
        }
        session.role = options(session, GameOption.Type.ROLE).stream()
                .filter(option -> option.name().equalsIgnoreCase(profile.preferredRole())).findFirst().orElse(null);
        return true;
    }

    private void renderExistingReply(ButtonInteractionEvent event, Session session) {
        GameObject mode = GameRepository.get(session.modeId);
        if (mode != null && mode.isSkillbased() && !options(session, GameOption.Type.RANK).isEmpty())
            renderReply(event, session, Step.RANK, 0);
        else if (!options(session, GameOption.Type.ROLE).isEmpty())
            renderReply(event, session, Step.ROLE, 0);
        else renderConfirmation(event, session);
    }

    private void renderExistingEdit(StringSelectInteractionEvent event, Session session) {
        GameObject mode = GameRepository.get(session.modeId);
        if (mode != null && mode.isSkillbased() && !options(session, GameOption.Type.RANK).isEmpty())
            renderEdit(event, session, Step.RANK, 0);
        else if (!options(session, GameOption.Type.ROLE).isEmpty())
            renderEdit(event, session, Step.ROLE, 0);
        else renderConfirmation(event, session);
    }

    private static List<ActionRow> confirmationRows(Session session) {
        List<Button> buttons = new ArrayList<>(List.of(
                        Button.success("gameWizardConfirm-" + session.token,
                                t(session.userId, "GameProfile.Confirmation.Confirm")),
                        Button.secondary("gameWizardCancel-" + session.token,
                                t(session.userId, "GameProfile.Confirmation.Cancel"))));
        if (session.tutorial) buttons.add(Button.danger("tutorialExit",
                t(session.userId, "Tutorial.Setup.Button.Exit")));
        else if (session.originalProfile != null) buttons.add(Button.danger(
                "gameProfileDeleteAsk-" + session.token,
                t(session.userId, "GameProfile.Delete.Button")));
        return List.of(ActionRow.of(buttons));
    }

    private static net.dv8tion.jda.api.entities.MessageEmbed confirmationEmbed(Session session) {
        return new EmbedCreator().setTitle(t(session.userId, session.flow == Flow.PASSIVE_QUEUE
                        ? "PassiveQ.Confirmation.Title" : "GameProfile.Confirmation.Title"))
                .setDescription(changeSummary(session)).build();
    }

    private static void renderConfirmation(StringSelectInteractionEvent event, Session session) {
        event.editMessageEmbeds(confirmationEmbed(session)).setComponents(confirmationRows(session)).queue();
    }

    private static void renderConfirmation(ButtonInteractionEvent event, Session session) {
        event.editMessageEmbeds(confirmationEmbed(session)).setComponents(confirmationRows(session)).queue();
    }

    private static Modal capacityModal(Session session) {
        return Modal.create("gameWizardLobby-" + session.token, t(session.userId, "Lobby.Wizard.Modal.Title"))
                .addComponents(Label.of(t(session.userId, "Lobby.Wizard.Modal.Capacity"),
                        TextInput.create("capacity", TextInputStyle.SHORT).setPlaceholder("5").setRequired(true)
                                .setMinLength(1).setMaxLength(2).build())).build();
    }

    private void renderReply(ButtonInteractionEvent event, Session session, Step step, int page) {
        if (choices(session, step).isEmpty()) {
            sessions.remove(session.token);
            event.editMessageEmbeds(new EmbedCreator().setTitle(t(session.userId, "Lobby.Wizard.Select.Game"))
                            .setDescription(t(session.userId, "Lobby.Wizard.NoGames")).build())
                    .setComponents(ActionRow.of(navigationButtons(session))).queue(); return;
        }
        SelectionPage selection = page(session, step, page);
        event.editMessageEmbeds(selection.embed).setComponents(
                ActionRow.of(selection.menu),
                ActionRow.of(navigationButtons(session))).queue();
    }

    private void renderEdit(StringSelectInteractionEvent event, Session session, Step step, int page) {
        SelectionPage selection = page(session, step, page);
        event.editMessageEmbeds(selection.embed).setComponents(
                ActionRow.of(selection.menu),
                ActionRow.of(navigationButtons(session))).queue();
    }

    private void renderError(StringSelectInteractionEvent event, Session session, String key) {
        event.editMessageEmbeds(new EmbedCreator().setDescription(t(session.userId, key)).build())
                .setComponents(ActionRow.of(navigationButtons(session))).queue();
    }

    private static List<Button> navigationButtons(Session session) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(session.tutorial
                ? Button.danger("tutorialExit", t(session.userId, "Tutorial.Setup.Button.Exit"))
                : Button.primary("mainPage", t(session.userId, "UserProfile.Button.Back")));
        if (!session.tutorial && session.originalProfile != null)
            buttons.add(Button.danger("gameProfileDeleteAsk-" + session.token,
                    t(session.userId, "GameProfile.Delete.Button")));
        return buttons;
    }

    private SelectionPage page(Session session, Step step, int requestedPage) {
        List<Choice> choices = choices(session, step);
        DropdownPagination.Page pagination = DropdownPagination.page(choices.size(), requestedPage);
        int page = pagination.index();
        int pages = pagination.count();
        int from = pagination.from();
        int to = pagination.to();
        List<SelectOption> options = new ArrayList<>();
        if (pagination.previous()) options.add(SelectOption.of(t(session.userId, "Lobby.Wizard.Navigation.Previous"), "nav:previous"));
        for (Choice choice : choices.subList(from, to))
            options.add(SelectOption.of(trim(choice.label, 100), "item:" + choice.id));
        if (pagination.next()) options.add(SelectOption.of(t(session.userId, "Lobby.Wizard.Navigation.Next"), "nav:next"));
        String title = switch (step) {
            case GAME -> t(session.userId, "Lobby.Wizard.Select.Game"); case MODE -> t(session.userId, "Lobby.Wizard.Select.Mode");
            case PLATFORM -> t(session.userId, "Lobby.Wizard.Select.Platform"); case REGION -> t(session.userId, "Lobby.Wizard.Select.Region");
            case RANK -> t(session.userId, "Lobby.Wizard.Select.Rank"); case ROLE -> t(session.userId, "Lobby.Wizard.Select.Role");
        };
        StringSelectMenu menu = StringSelectMenu.create("gameWizard-" + session.token + "-" + step + "-" + page)
                .setPlaceholder(t(session.userId, "Lobby.Wizard.Page", Map.of("%title%", title,
                        "%page%", String.valueOf(page + 1), "%pages%", String.valueOf(pages)))).addOptions(options).build();
        String descriptionKey = session.profilePlatformSelection ? "Lobby.Wizard.SelectProfileDescription"
                : session.creatingProfileForLobby ? "Lobby.Wizard.CreateProfileDescription" : "Lobby.Wizard.Description";
        return new SelectionPage(new EmbedCreator().setTitle(title)
                .setDescription(t(session.userId, descriptionKey, Map.of("%page%", String.valueOf(page + 1),
                        "%pages%", String.valueOf(pages)))).build(), menu, options);
    }

    private List<Choice> choices(Session session, Step step) {
        return switch (step) {
            case GAME -> GameRepository.getAllMainGames().stream().filter(GameObject::isActive)
                    .map(game -> new Choice(game.getId(), game.getName())).toList();
            case MODE -> modes(session.mainGameId).stream().map(game -> new Choice(game.getId(), game.getName())).toList();
            case PLATFORM -> session.profilePlatformSelection
                    ? profilePlatformChoices(session) : optionChoices(options(session, GameOption.Type.PLATFORM));
            case REGION -> optionChoices(options(session, GameOption.Type.REGION));
            case RANK -> optionChoices(options(session, GameOption.Type.RANK));
            case ROLE -> optionChoices(options(session, GameOption.Type.ROLE));
        };
    }

    private static List<Choice> profilePlatformChoices(Session session) {
        List<GameProfile> profiles = GameProfileRepository.getForGame(session.userId, session.modeId);
        List<Choice> choices = new ArrayList<>();
        for (int i = 0; i < profiles.size(); i++) choices.add(new Choice(i, profiles.get(i).platform()));
        return choices;
    }

    private static List<Choice> optionChoices(List<GameOption> options) {
        return options.stream().map(option -> new Choice(option.id(), option.name())).toList();
    }

    private static List<GameObject> modes(int mainGameId) {
        return GameRepository.getSubGames(mainGameId).stream().filter(GameObject::isActive).toList();
    }

    private static List<GameOption> options(Session session, GameOption.Type type) {
        return GameOptionRepository.get(session.modeId, type);
    }

    private static GameOption option(Session session, int id, GameOption.Type expectedType) {
        GameOption option = GameOptionRepository.getById(id);
        boolean allowed = options(session, expectedType).stream().anyMatch(candidate -> candidate.id() == id);
        if (option == null || option.type() != expectedType || !allowed) throw new IllegalArgumentException("Ungültige Auswahl");
        return option;
    }

    private static String changeSummary(Session session) {
        GameProfile old = session.originalProfile;
        return t(session.userId, GameMessageVisibility.profileVariantKey(
                "GameProfile.Confirmation.Description", session.modeId), Map.of(
                "%game%", gameDisplayName(session.modeId),
                "%oldPlatform%", old == null ? "-" : old.platform(),
                "%oldRegion%", old == null ? "-" : old.region(),
                "%oldRank%", old == null ? "-" : rankName(session.modeId, old.rankValue()),
                "%oldRole%", old == null ? "-" : old.preferredRole(),
                "%newPlatform%", session.platform.name(),
                "%newRegion%", session.region.name(),
                "%newRank%", session.rank == null ? "-" : session.rank.name(),
                "%newRole%", session.role == null ? "-" : session.role.name()));
    }

    private static String gameDisplayName(int modeId) {
        GameObject game = GameRepository.get(modeId);
        if (game == null) return Integer.toString(modeId);
        GameObject parent = game.getSubGameFrom();
        return parent == null ? game.getName() : parent.getName() + " · " + game.getName();
    }

    private static String rankName(int gameId, int rankValue) {
        return GameOptionRepository.get(gameId, GameOption.Type.RANK).stream()
                .filter(option -> option.sortOrder() == rankValue).map(GameOption::name)
                .findFirst().orElse(rankValue == 0 ? "-" : String.valueOf(rankValue));
    }

    private void cleanup() { sessions.values().removeIf(GameSelectionWizard::expired); }
    private static boolean expired(Session session) { return session.createdAt.plus(SESSION_LIFETIME).isBefore(Instant.now()); }
    private static String trim(String value, int max) { return value.length() <= max ? value : value.substring(0, max - 1) + "…"; }
    private static String t(int userId, String key) { return LanguageManager.getMessageForUser(key, userId); }
    private static String t(int userId, String key, Map<String, String> replacements) {
        return LanguageManager.getMessageForUser(key, userId, replacements);
    }

    private record Choice(int id, String label) {}
    private record SelectionPage(net.dv8tion.jda.api.entities.MessageEmbed embed, StringSelectMenu menu,
                                 List<SelectOption> options) {}
    private record LobbyLanguagePage(net.dv8tion.jda.api.entities.MessageEmbed embed, List<ActionRow> rows) {}
    private static final class Session {
        final String token; final int userId; final Flow flow; final Instant createdAt;
        int mainGameId; int modeId; GameOption platform; GameOption region; GameOption rank; GameOption role;
        int capacity; boolean profilePlatformSelection; boolean creatingProfileForLobby; GameProfile selectedProfile;
        GameProfile originalProfile;
        boolean passiveAutoEnabled;
        boolean autoDeleteOnComplete;
        boolean tutorial;
        List<String> languages = List.of();
        Session(String token, int userId, Flow flow, Instant createdAt) {
            this.token = token; this.userId = userId; this.flow = flow; this.createdAt = createdAt;
        }
    }
}
