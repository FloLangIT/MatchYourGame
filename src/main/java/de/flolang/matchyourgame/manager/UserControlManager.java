package de.flolang.matchyourgame.manager;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.database.user.InboxMessageRepository;
import de.flolang.matchyourgame.database.user.FriendRequestPolicy;
import de.flolang.matchyourgame.database.friend.FriendRepository;
import de.flolang.matchyourgame.database.friend.FriendObject;
import de.flolang.matchyourgame.database.game.GameObject;
import de.flolang.matchyourgame.database.game.GameRepository;
import de.flolang.matchyourgame.database.game.GameOption;
import de.flolang.matchyourgame.database.game.GameOptionRepository;
import de.flolang.matchyourgame.database.game.RankCompatibilityRepository;
import de.flolang.matchyourgame.database.lobby.LobbyObject;
import de.flolang.matchyourgame.database.lobby.LobbyStatus;
import de.flolang.matchyourgame.database.lobby.LobbyRepository;
import de.flolang.matchyourgame.database.profile.GameProfile;
import de.flolang.matchyourgame.database.profile.GameProfileRepository;
import de.flolang.matchyourgame.database.profile.CommunicationLanguageRepository;
import de.flolang.matchyourgame.database.party.PartyObject;
import de.flolang.matchyourgame.database.party.PartyRepository;
import de.flolang.matchyourgame.database.lobby.PassiveQueueSettings;
import de.flolang.matchyourgame.database.lobby.PassiveQueueSettingsRepository;
import de.flolang.matchyourgame.database.lobby.SearchProfileRepository;
import de.flolang.matchyourgame.database.review.ReviewRepository;
import de.flolang.matchyourgame.database.lobby.ClanRepository;
import de.flolang.matchyourgame.database.guild.GuildRepository;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.language.CommunicationLanguageNames;
import de.flolang.matchyourgame.manager.lobby.RankDisplayFormatter;
import de.flolang.matchyourgame.manager.lobby.GameMessageVisibility;
import de.flolang.matchyourgame.manager.review.RatingFormatter;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Message;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import java.sql.Timestamp;

public class UserControlManager {

    private Message message;
    private UserObject userObject;

    public UserControlManager(Message message, UserObject userObject) {
        this.message = message;
        this.userObject = userObject;
    }

    public void loadStartPage() {
        if (!message.isPinned()) {
            message.pin().queue();
        }
        HashMap<String, String> replacings = new HashMap<>();
        replacings.put("%userId%", String.valueOf(userObject.getId()));
        replacings.put("%username%", userObject.getUsername());
        replacings.put("%createdAt%", "<t:" + userObject.getCreatedAt().getTime() / 1000 + ":R>");
        replacings.put("%rating%", ratingLabel(userObject.getId()));
        PartyObject party = PartyRepository.getForUser(userObject.getId());
        LobbyObject activeLobby = LobbyRepository.getActiveForUser(userObject.getId());
        PassiveQueueSettings passiveSettings = PassiveQueueSettingsRepository.get(userObject.getId());
        long passiveGameCount = SearchProfileRepository.getForUser(userObject.getId()).stream()
                .filter(profile -> profile.passiveEnabled()).map(profile -> profile.gameId()).distinct().count();
        replacings.put("%passiveStatus%", LanguageManager.getMessageForUser(
                passiveSettings.enabled() ? "General.On" : "General.Off", userObject.getId()));
        replacings.put("%passiveGamesLabel%", LanguageManager.getMessageForUser(
                passiveGameCount == 1 ? "UserProfile.PassiveGames.One" : "UserProfile.PassiveGames.Other",
                userObject.getId(), Map.of("%count%", String.valueOf(passiveGameCount))));
        int totalFriends = FriendRepository.getAcceptedFriendIds(userObject.getId()).size();
        replacings.put("%party%", party == null
                ? LanguageManager.getMessageForUser("UserProfile.PartyStatus.None", userObject.getId())
                : LanguageManager.getMessageForUser("UserProfile.PartyStatus.Active", userObject.getId(),
                        Map.of("%count%", String.valueOf(party.memberIds().size()))));
        replacings.put("%activeFriends%", String.valueOf(countActiveFriends()));
        replacings.put("%totalFriends%", String.valueOf(totalFriends));
        String communicationLanguages = CommunicationLanguageRepository.getForUser(userObject.getId()).stream()
                .map(language -> CommunicationLanguageNames.displayName(language.code(), userObject.getLanguage()))
                .reduce((first, next) -> first + ", " + next).orElse("-");
        replacings.put("%communicationLanguages%", communicationLanguages);
        int inboxCount = InboxMessageRepository.count(userObject.getId(), false);
        int unreadInbox = InboxMessageRepository.count(userObject.getId(), true);
        replacings.put("%inboxNotice%", LanguageManager.getMessageForUser(unreadInbox == 0
                ? "UserProfile.InboxStatus.None" : "UserProfile.InboxStatus.Unread", userObject.getId(),
                Map.of("%count%", String.valueOf(unreadInbox))));
        List<ActionRow> mainRows = new ArrayList<>();
        mainRows.add(ActionRow.of(Button.primary("editProfile", LanguageManager.getMessageForUser("UserProfile.Button.EditProfile", userObject.getId())),
                Button.primary("gameProfiles", LanguageManager.getMessageForUser("UserProfile.Button.GameProfiles", userObject.getId()))));
        mainRows.add(ActionRow.of(Button.secondary("passiveQ", LanguageManager.getMessageForUser("UserProfile.Button.PassiveQ", userObject.getId()))
                        .withDisabled(activeLobby != null),
                (passiveSettings.enabled()
                        ? Button.danger("passiveGlobalShortcut", LanguageManager.getMessageForUser(
                                "UserProfile.Button.DisablePassiveQ", userObject.getId()))
                        : Button.success("passiveGlobalShortcut", LanguageManager.getMessageForUser(
                                "UserProfile.Button.EnablePassiveQ", userObject.getId())))
                        .withDisabled(activeLobby != null),
                Button.success("createLobby", LanguageManager.getMessageForUser(activeLobby == null
                        ? "UserProfile.Button.CreateLobby" : "UserProfile.Button.ManageLobby", userObject.getId()))));
        mainRows.add(ActionRow.of(Button.success("friends", LanguageManager.getMessageForUser("UserProfile.Button.Friends", userObject.getId())),
                Button.success("crews", LanguageManager.getMessageForUser("UserProfile.Button.Crews", userObject.getId())),
                Button.secondary("party", LanguageManager.getMessageForUser(
                        party == null ? "UserProfile.Button.PartyCreate" : "UserProfile.Button.PartyManage", userObject.getId()))));
        List<Button> finalRow = new ArrayList<>();
        finalRow.add(Button.danger("reportMenu", LanguageManager.getMessageForUser("UserProfile.Button.Report", userObject.getId())));
        if (inboxCount > 0)
            finalRow.add((unreadInbox > 0 ? Button.success("inbox", LanguageManager.getMessageForUser(
                            "UserProfile.Button.InboxUnread", userObject.getId(), Map.of("%count%", String.valueOf(unreadInbox))))
                    : Button.secondary("inbox", LanguageManager.getMessageForUser(
                            "UserProfile.Button.Inbox", userObject.getId()))));
        if (!GuildRepository.getManagedBy(userObject.getId()).isEmpty())
            finalRow.add(Button.success("guildManager", LanguageManager.getMessageForUser(
                    "UserProfile.Button.GuildManager", userObject.getId())));
        if (AdminAccess.role(userObject) != de.flolang.matchyourgame.database.user.UserRole.USER)
            finalRow.add(Button.secondary("adminPanel", LanguageManager.getMessageForUser(
                    "UserProfile.Button.AdminPanel", userObject.getId())));
        mainRows.add(ActionRow.of(finalRow));
        message.editMessageEmbeds(LanguageManager.getEmbedForUser("UserProfile", userObject.getId(), replacings).build())
                .setComponents(mainRows).queue();
    }

    public void loadReportPage() {
        message.editMessageEmbeds(LanguageManager.getEmbedForUser(
                        "Report.Menu", userObject.getId(), new HashMap<>()).build())
                .setComponents(
                        ActionRow.of(
                                Button.danger("reportPlayer", t("Report.Menu.Player")),
                                Button.primary("reportBug", t("Report.Menu.Bug")),
                                Button.secondary("reportFeedback", t("Report.Menu.Feedback"))),
                        ActionRow.of(Button.primary("mainPage", t("UserProfile.Button.Back"))))
                .queue();
    }

    public void loadLobbyPage(LobbyObject lobby) {
        if (lobby == null || !LobbyRepository.memberIds(lobby.getId()).contains(userObject.getId())) {
            loadStartPage();
            return;
        }
        HashMap<String, String> replacements = new HashMap<>();
        replacements.put("%lobbyId%", String.valueOf(lobby.getId()));
        replacements.put("%game%", gameDisplayName(lobby.getGameID()));
        replacements.put("%players%", String.valueOf(LobbyRepository.memberCount(lobby.getId())));
        replacements.put("%capacity%", String.valueOf(lobby.getMaxPlayers()));
        replacements.put("%platform%", lobby.getPlatform());
        replacements.put("%region%", lobby.getRegion());
        String languageLabel = de.flolang.matchyourgame.database.lobby.LobbyLanguageRepository.get(lobby.getId()).stream()
                .map(code -> CommunicationLanguageNames.displayName(code, userObject.getLanguage()))
                .reduce((first, next) -> first + ", " + next).orElse("");
        if (languageLabel.isBlank()) languageLabel = t("Lobby.View.AnyLanguage");
        replacements.put("%languages%", languageLabel);
        replacements.put("%language%", languageLabel);
        if (GameMessageVisibility.showsRanks(lobby.getGameID()))
            replacements.put("%ranks%", compatibleRankLabel(lobby));
        replacements.put("%playerList%", lobbyPlayerList(lobby));
        replacements.put("%voiceInvite%", lobby.getVoiceInviteUrl() == null || lobby.getVoiceInviteUrl().isBlank()
                ? t(lobby.getVoiceChannelID() == 0 ? "Lobby.View.VoiceNotReady" : "Lobby.View.VoicePreparing")
                : "[" + t("Lobby.View.JoinVoice") + "](" + lobby.getVoiceInviteUrl() + ")");
        List<ActionRow> rows = new ArrayList<>();
        boolean host = lobby.getLeaderID() == userObject.getId();
        if (host && lobby.getStatus() == LobbyStatus.OPEN) {
            rows.add(ActionRow.of(
                    Button.success("lobbyInviteFriend-" + lobby.getId(), t("Lobby.Button.InviteFriend")),
                    Button.success("lobbyInviteClan-" + lobby.getId(), t("Lobby.Button.InviteClan"))));
            rows.add(ActionRow.of(
                    Button.secondary("lobbyTogglePassive-" + lobby.getId(), t(lobby.isPassiveQueue()
                            ? "Lobby.Button.DeactivatePassive" : "Lobby.Button.ActivatePassive")),
                    Button.primary("lobbySettings-" + lobby.getId(), t("Lobby.Button.Settings")),
                    Button.danger("lobbyClose-" + lobby.getId(), t("Lobby.Button.Close"))));
        } else if (host) {
            List<Button> actions = new ArrayList<>();
            if (List.of(LobbyStatus.FORMING, LobbyStatus.READY, LobbyStatus.ACTIVE).contains(lobby.getStatus()))
                actions.add(Button.primary("matchEntryOverview-" + lobby.getId(), t("Match.Button.Add")));
            actions.add(Button.danger("lobbyClose-" + lobby.getId(), t("Lobby.Button.Close")));
            rows.add(ActionRow.of(actions));
        }
        if (host) {
            List<SelectOption> kickable = LobbyRepository.memberIds(lobby.getId()).stream()
                    .filter(memberId -> memberId != userObject.getId()).map(UserController::get)
                    .filter(java.util.Objects::nonNull).limit(25)
                    .map(member -> SelectOption.of(member.getUsername(), String.valueOf(member.getId()))).toList();
            if (!kickable.isEmpty()) rows.add(ActionRow.of(StringSelectMenu.create("lobbyKickMember-" + lobby.getId())
                    .setPlaceholder(t("Lobby.Kick.Select")).addOptions(kickable).build()));
        }
        rows.add(ActionRow.of(
                Button.danger("lobbyLeave-" + lobby.getId(), t("Lobby.Button.Leave")),
                Button.primary("mainPage", t("UserProfile.Button.Back"))));
        String description = t(GameMessageVisibility.showsRanks(lobby.getGameID())
                ? "Lobby.View.Description" : "Lobby.View.DescriptionNoRank", replacements)
                .replace("%languages%", languageLabel);
        message.editMessageEmbeds(new EmbedCreator()
                        .setTitle(t("Lobby.View.Title", replacements))
                        .setDescription(description).build())
                .setComponents(rows).queue();
    }

    public void loadGameProfilesPage() {
        loadGameProfilesPage(0);
    }

    public void loadGameProfilesPage(int requestedPage) {
        List<GameProfile> profiles = GameProfileRepository.getForUser(userObject.getId());
        int pages = Math.max(1, (profiles.size() + 22) / 23);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int from = Math.min(page * 23, profiles.size());
        int to = Math.min(from + 23, profiles.size());
        List<GameProfile> displayed = profiles.subList(from, to);
        String entries = displayed.stream().map(profile -> t(GameMessageVisibility.profileVariantKey(
                "GameProfile.Manage.Entry", profile.gameId()), Map.of(
                        "%game%", gameDisplayName(profile.gameId()),
                        "%platform%", profile.platform(),
                        "%region%", profile.region(),
                        "%rank%", rankName(profile.gameId(), profile.rankValue()),
                        "%role%", profile.preferredRole())))
                .reduce((first, next) -> first + "\n\n" + next)
                .orElse(t("GameProfile.Manage.None"));
        HashMap<String, String> replacements = new HashMap<>();
        replacements.put("%profiles%", entries);
        replacements.put("%count%", String.valueOf(profiles.size()));
        replacements.put("%page%", String.valueOf(page + 1));
        replacements.put("%pages%", String.valueOf(pages));
        List<ActionRow> rows = new ArrayList<>();
        if (!displayed.isEmpty()) rows.add(ActionRow.of(StringSelectMenu.create("gameProfileSelect-" + page)
                .setPlaceholder(t("GameProfile.Manage.Select"))
                .addOptions(displayed.stream().map(profile -> SelectOption.of(
                                trim(gameDisplayName(profile.gameId()) + " · " + profile.platform(), 100),
                                profile.gameId() + "|" + profile.platform())
                        .withDescription(trim(profile.region() + " · " + rankName(profile.gameId(), profile.rankValue())
                                + " · " + profile.preferredRole(), 100))).toList()).build()));
        if (pages > 1) rows.add(ActionRow.of(
                Button.secondary("gameProfilesPage-" + Math.max(0, page - 1), t("General.Previous"))
                        .withDisabled(page == 0),
                Button.secondary("gameProfilesPage-" + Math.min(pages - 1, page + 1), t("General.Next"))
                        .withDisabled(page >= pages - 1)));
        rows.add(ActionRow.of(
                Button.success("gameProfileConfigure", t("GameProfile.Manage.Configure")),
                Button.secondary("communicationLanguages", t("GameProfile.Manage.Languages")),
                Button.primary("mainPage", t("UserProfile.Button.Back"))));
        message.editMessageEmbeds(LanguageManager.getEmbedForUser("GameProfile.Manage", userObject.getId(), replacements).build())
                .setComponents(rows).queue();
    }

    public void loadEditProfilePage() {
        String communicationLanguages = CommunicationLanguageRepository.getForUser(userObject.getId()).stream()
                .map(language -> CommunicationLanguageNames.displayName(language.code(), userObject.getLanguage())
                        + " (#" + language.priority() + ")")
                .reduce((first, next) -> first + ", " + next).orElse("-");
        FriendRequestPolicy policy = UserRepository.getFriendRequestPolicy(userObject.getId());
        HashMap<String, String> replacements = new HashMap<>();
        replacements.put("%username%", userObject.getUsername());
        replacements.put("%messageLanguage%", CommunicationLanguageNames.displayName(
                userObject.getLanguage().name(), userObject.getLanguage()));
        replacements.put("%communicationLanguages%", communicationLanguages);
        replacements.put("%friendRequestPolicy%", t("UserProfile.Edit.FriendRequests." + policy.name()));

        List<SelectOption> languages = java.util.Arrays.stream(de.flolang.matchyourgame.language.Language.values())
                .map(language -> SelectOption.of(CommunicationLanguageNames.displayName(
                                language.name(), userObject.getLanguage()), language.name())
                        .withDefault(language == userObject.getLanguage())).toList();
        List<SelectOption> policies = java.util.Arrays.stream(FriendRequestPolicy.values())
                .map(value -> SelectOption.of(t("UserProfile.Edit.FriendRequests." + value.name()), value.name())
                        .withDefault(value == policy)).toList();
        message.editMessageEmbeds(LanguageManager.getEmbedForUser("UserProfile.Edit", userObject.getId(), replacements).build())
                .setComponents(
                        ActionRow.of(Button.primary("profileUsername", t("UserProfile.Edit.Button.Username")),
                                Button.secondary("profileCommunicationLanguages",
                                        t("UserProfile.Edit.Button.CommunicationLanguages"))),
                        ActionRow.of(StringSelectMenu.create("profileMessageLanguage")
                                .setPlaceholder(t("UserProfile.Edit.Select.MessageLanguage")).addOptions(languages).build()),
                        ActionRow.of(StringSelectMenu.create("profileFriendRequestPolicy")
                                .setPlaceholder(t("UserProfile.Edit.Select.FriendRequests")).addOptions(policies).build()),
                        ActionRow.of(Button.danger("profileDeleteAccount", t("UserProfile.Edit.Button.DeleteAccount")),
                                Button.primary("mainPage", t("UserProfile.Button.Back"))))
                .queue();
    }

    public void loadCommunicationLanguagesPage() {
        loadCommunicationLanguagesPage(false);
    }

    public void loadCommunicationLanguagesPage(boolean returnToProfile) {
        String languages = CommunicationLanguageRepository.getForUser(userObject.getId()).stream()
                .map(language -> "**#" + language.priority() + "** · "
                        + CommunicationLanguageNames.displayName(language.code(), userObject.getLanguage()))
                .reduce((first, next) -> first + "\n" + next).orElse("-");
        HashMap<String, String> replacements = new HashMap<>();
        replacements.put("%languages%", languages);
        message.editMessageEmbeds(LanguageManager.getEmbedForUser("GameProfile.Languages", userObject.getId(), replacements).build())
                .setComponents(ActionRow.of(
                        Button.success(returnToProfile ? "profileCommunicationLanguageAdd" : "communicationLanguageAdd",
                                t("GameProfile.Languages.Add")),
                        Button.primary(returnToProfile ? "editProfile" : "gameProfiles",
                                t("UserProfile.Button.Back")))).queue();
    }

    public void loadFriendsMainPage() {
        loadFriendsMainPage(0);
    }

    public void loadCrewsPage() {
        loadCrewsPage(0);
    }

    public void loadCrewsPage(int requestedPage) {
        List<ClanRepository.ClanInfo> clans = ClanRepository.forUser(userObject.getId());
        int pages = Math.max(1, (clans.size() + 22) / 23);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int from = Math.min(page * 23, clans.size());
        int to = Math.min(from + 23, clans.size());
        String entries = clans.subList(from, to).stream().map(clan -> t("UserProfile.Crews.Entry", Map.of(
                        "%name%", clan.name(),
                        "%role%", t("UserProfile.Crews.Role." + clan.role().name()),
                        "%members%", String.valueOf(clan.memberCount()))))
                .reduce((first, next) -> first + "\n" + next).orElse(t("UserProfile.Crews.None"));
        HashMap<String, String> replacements = new HashMap<>();
        replacements.put("%count%", String.valueOf(clans.size()));
        replacements.put("%page%", String.valueOf(page + 1));
        replacements.put("%pages%", String.valueOf(pages));
        replacements.put("%crews%", entries);
        List<ActionRow> rows = new ArrayList<>();
        if (from < to) rows.add(ActionRow.of(StringSelectMenu.create("crewSelect-" + page)
                .setPlaceholder(t("UserProfile.Crews.Select"))
                .addOptions(clans.subList(from, to).stream()
                        .map(clan -> SelectOption.of(clan.name(), String.valueOf(clan.id()))).toList()).build()));
        if (pages > 1) rows.add(ActionRow.of(
                Button.secondary("crewsPage-" + Math.max(0, page - 1), t("General.Previous")).withDisabled(page == 0),
                Button.secondary("crewsPage-" + Math.min(pages - 1, page + 1), t("General.Next"))
                        .withDisabled(page >= pages - 1)));
        rows.add(ActionRow.of(
                Button.success("crewCreate", t("UserProfile.Crews.Create.Button")),
                Button.primary("mainPage", t("UserProfile.Button.Back"))));
        message.editMessageEmbeds(LanguageManager.getEmbedForUser("UserProfile.Crews", userObject.getId(), replacements).build())
                .setComponents(rows).queue();
    }

    public void loadCrewPage(int crewId, int returnPage) {
        ClanRepository.ClanInfo clan = ClanRepository.getForMember(crewId, userObject.getId());
        if (clan == null) { loadCrewsPage(returnPage); return; }
        UserObject owner = UserController.get(clan.ownerUserId());
        String members = ClanRepository.memberDetails(crewId).stream().map(entry -> {
                    UserObject member = UserController.get(entry.userId());
                    return member == null ? null : "• " + member.getUsername() + " · "
                            + t("UserProfile.Crews.Role." + entry.role().name());
                }).filter(Objects::nonNull)
                .reduce((first, next) -> first + "\n" + next).orElse("-");
        if (members.length() > 2500) members = members.substring(0, 2497) + "...";
        HashMap<String, String> replacements = new HashMap<>();
        replacements.put("%name%", clan.name());
        replacements.put("%id%", String.valueOf(clan.id()));
        replacements.put("%owner%", owner == null ? "-" : owner.getUsername());
        replacements.put("%role%", t("UserProfile.Crews.Role." + clan.role().name()));
        replacements.put("%createdAt%", discordTime(clan.createdAt()));
        replacements.put("%memberCount%", String.valueOf(clan.memberCount()));
        replacements.put("%members%", members);
        List<ActionRow> rows = new ArrayList<>();
        if (clan.role() != ClanRepository.Role.MEMBER) rows.add(ActionRow.of(
                Button.success("crewInvite-" + crewId + "-" + returnPage, t("UserProfile.Crews.Actions.Invite")),
                Button.secondary("crewMembers-" + crewId + "-0-" + returnPage, t("UserProfile.Crews.Actions.Members"))));
        if (clan.role() == ClanRepository.Role.LEADER || clan.role() == ClanRepository.Role.ADMIN) rows.add(ActionRow.of(
                Button.secondary("crewGames-" + crewId + "-0-" + returnPage, t("UserProfile.Crews.Actions.Games")),
                Button.secondary("crewRename-" + crewId + "-" + returnPage, t("UserProfile.Crews.Actions.Rename"))));
        if (clan.role() == ClanRepository.Role.LEADER) rows.add(ActionRow.of(
                Button.danger("crewDeleteAsk-" + crewId + "-" + returnPage, t("UserProfile.Crews.Actions.Delete"))));
        else rows.add(ActionRow.of(Button.danger("crewLeave-" + crewId + "-" + returnPage,
                t("UserProfile.Crews.Actions.Leave"))));
        rows.add(ActionRow.of(Button.primary("crewsPage-" + returnPage, t("UserProfile.Button.Back"))));
        message.editMessageEmbeds(LanguageManager.getEmbedForUser("UserProfile.Crews.Detail", userObject.getId(), replacements).build())
                .setComponents(rows).queue();
    }

    public void loadCrewMembersPage(int crewId, int requestedPage, int returnPage) {
        ClanRepository.ClanInfo clan = ClanRepository.getForMember(crewId, userObject.getId());
        if (clan == null || clan.role() == ClanRepository.Role.MEMBER) { loadCrewsPage(returnPage); return; }
        List<ClanRepository.MemberInfo> members = ClanRepository.memberDetails(crewId);
        int pages = Math.max(1, (members.size() + 22) / 23);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int from = Math.min(page * 23, members.size()), to = Math.min(from + 23, members.size());
        List<SelectOption> options = members.subList(from, to).stream().map(member -> {
            UserObject account = UserController.get(member.userId());
            return SelectOption.of(account == null ? "#" + member.userId() : account.getUsername(), String.valueOf(member.userId()))
                    .withDescription(t("UserProfile.Crews.Role." + member.role().name()));
        }).toList();
        HashMap<String, String> replacements = new HashMap<>();
        replacements.put("%name%", clan.name()); replacements.put("%page%", String.valueOf(page + 1));
        replacements.put("%pages%", String.valueOf(pages));
        List<ActionRow> rows = new ArrayList<>();
        if (!options.isEmpty()) rows.add(ActionRow.of(StringSelectMenu.create(
                        "crewMemberSelect-" + crewId + "-" + page + "-" + returnPage)
                .setPlaceholder(t("UserProfile.Crews.Members.Select")).addOptions(options).build()));
        if (pages > 1) rows.add(ActionRow.of(
                Button.secondary("crewMembers-" + crewId + "-" + Math.max(0, page - 1) + "-" + returnPage,
                        t("General.Previous")).withDisabled(page == 0),
                Button.secondary("crewMembers-" + crewId + "-" + Math.min(pages - 1, page + 1) + "-" + returnPage,
                        t("General.Next")).withDisabled(page >= pages - 1)));
        rows.add(ActionRow.of(Button.primary("crewOpen-" + crewId + "-" + returnPage, t("UserProfile.Button.Back"))));
        message.editMessageEmbeds(LanguageManager.getEmbedForUser("UserProfile.Crews.Members", userObject.getId(), replacements).build())
                .setComponents(rows).queue();
    }

    public void loadCrewMemberPage(int crewId, int targetId, int membersPage, int returnPage) {
        ClanRepository.ClanInfo clan = ClanRepository.getForMember(crewId, userObject.getId());
        ClanRepository.Role targetRole = ClanRepository.roleOf(crewId, targetId);
        UserObject target = UserController.get(targetId);
        if (clan == null || clan.role() == ClanRepository.Role.MEMBER || targetRole == null || target == null) {
            loadCrewMembersPage(crewId, membersPage, returnPage); return;
        }
        HashMap<String, String> replacements = new HashMap<>();
        replacements.put("%username%", target.getUsername());
        replacements.put("%role%", t("UserProfile.Crews.Role." + targetRole.name()));
        List<ActionRow> rows = new ArrayList<>();
        List<SelectOption> roles = new ArrayList<>();
        if (clan.role() == ClanRepository.Role.LEADER) {
            roles.add(SelectOption.of(t("UserProfile.Crews.Role.LEADER"), "LEADER"));
        }
        if (clan.role() == ClanRepository.Role.LEADER || clan.role() == ClanRepository.Role.ADMIN) {
            roles.add(SelectOption.of(t("UserProfile.Crews.Role.ADMIN"), "ADMIN"));
            roles.add(SelectOption.of(t("UserProfile.Crews.Role.MODERATOR"), "MODERATOR"));
            roles.add(SelectOption.of(t("UserProfile.Crews.Role.MEMBER"), "MEMBER"));
        }
        if (targetId != userObject.getId() && !roles.isEmpty()) rows.add(ActionRow.of(StringSelectMenu.create(
                        "crewRole-" + crewId + "-" + targetId + "-" + membersPage + "-" + returnPage)
                .setPlaceholder(t("UserProfile.Crews.Members.ChangeRole")).addOptions(roles).build()));
        if (targetId != userObject.getId() && targetRole != ClanRepository.Role.LEADER) rows.add(ActionRow.of(
                Button.danger("crewKick-" + crewId + "-" + targetId + "-" + membersPage + "-" + returnPage,
                        t("UserProfile.Crews.Actions.Kick"))));
        rows.add(ActionRow.of(Button.primary("crewMembers-" + crewId + "-" + membersPage + "-" + returnPage,
                t("UserProfile.Button.Back"))));
        message.editMessageEmbeds(LanguageManager.getEmbedForUser("UserProfile.Crews.Member", userObject.getId(), replacements).build())
                .setComponents(rows).queue();
    }

    public void loadCrewGamesPage(int crewId, int requestedPage, int returnPage) {
        ClanRepository.ClanInfo clan = ClanRepository.getForMember(crewId, userObject.getId());
        if (clan == null || (clan.role() != ClanRepository.Role.LEADER && clan.role() != ClanRepository.Role.ADMIN)) {
            loadCrewsPage(returnPage); return;
        }
        List<GameObject> games = GameRepository.getAllMainGames().stream().filter(GameObject::isActive).toList();
        Set<Integer> enabled = ClanRepository.games(crewId);
        int pages = Math.max(1, (games.size() + 22) / 23);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int from = Math.min(page * 23, games.size()), to = Math.min(from + 23, games.size());
        List<SelectOption> options = games.subList(from, to).stream().map(game -> SelectOption.of(
                (enabled.contains(game.getId()) ? "✓ " : "○ ") + game.getName(), String.valueOf(game.getId()))).toList();
        String configured = games.stream().filter(game -> enabled.contains(game.getId())).map(GameObject::getName)
                .reduce((a, b) -> a + ", " + b).orElse(t("UserProfile.Crews.Games.None"));
        HashMap<String, String> replacements = new HashMap<>();
        replacements.put("%name%", clan.name()); replacements.put("%games%", configured);
        replacements.put("%page%", String.valueOf(page + 1)); replacements.put("%pages%", String.valueOf(pages));
        List<ActionRow> rows = new ArrayList<>();
        if (!options.isEmpty()) rows.add(ActionRow.of(StringSelectMenu.create(
                        "crewGameToggle-" + crewId + "-" + page + "-" + returnPage)
                .setPlaceholder(t("UserProfile.Crews.Games.Select")).addOptions(options).build()));
        if (pages > 1) rows.add(ActionRow.of(
                Button.secondary("crewGames-" + crewId + "-" + Math.max(0, page - 1) + "-" + returnPage,
                        t("General.Previous")).withDisabled(page == 0),
                Button.secondary("crewGames-" + crewId + "-" + Math.min(pages - 1, page + 1) + "-" + returnPage,
                        t("General.Next")).withDisabled(page >= pages - 1)));
        rows.add(ActionRow.of(Button.primary("crewOpen-" + crewId + "-" + returnPage, t("UserProfile.Button.Back"))));
        message.editMessageEmbeds(LanguageManager.getEmbedForUser("UserProfile.Crews.Games", userObject.getId(), replacements).build())
                .setComponents(rows).queue();
    }

    public void loadFriendsMainPage(int requestedPage) {
        List<Integer> friends = FriendRepository.getAcceptedFriendIds(userObject.getId());
        int pages = Math.max(1, (friends.size() + 22) / 23);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int from = Math.min(page * 23, friends.size());
        int to = Math.min(from + 23, friends.size());
        HashMap<String, String> replacings = new HashMap<>();
        replacings.put("%count%", String.valueOf(friends.size()));
        replacings.put("%page%", String.valueOf(page + 1));
        replacings.put("%pages%", String.valueOf(pages));
        List<ActionRow> rows = new ArrayList<>();
        if (from < to) {
            List<SelectOption> options = friends.subList(from, to).stream().map(UserController::get)
                    .filter(friend -> friend != null)
                    .map(friend -> SelectOption.of(friend.getUsername(), String.valueOf(friend.getId()))).toList();
            rows.add(ActionRow.of(StringSelectMenu.create("friendSelect-" + page)
                    .setPlaceholder(LanguageManager.getMessageForUser("UserProfile.Friends.Dropdown", userObject.getId()))
                    .addOptions(options).build()));
        }
        if (pages > 1) rows.add(ActionRow.of(
                Button.secondary("friendsPage-" + Math.max(0, page - 1),
                        LanguageManager.getMessageForUser("General.Previous", userObject.getId())).withDisabled(page == 0),
                Button.secondary("friendsPage-" + Math.min(pages - 1, page + 1),
                        LanguageManager.getMessageForUser("General.Next", userObject.getId())).withDisabled(page >= pages - 1)));
        rows.add(ActionRow.of(Button.success("addFriend", LanguageManager.getMessageForUser("UserProfile.Friends.Button.AddFriend", userObject.getId())),
                Button.primary("mainPage", LanguageManager.getMessageForUser("UserProfile.Button.Back", userObject.getId()))));
        message.editMessageEmbeds(LanguageManager.getEmbedForUser("UserProfile.Friends", userObject.getId(), replacings).build())
                .setComponents(rows).queue();
    }

    public void loadFriendProfile(int friendId, int returnPage) {
        UserObject friend = UserController.get(friendId);
        FriendObject friendship = FriendRepository.get(userObject.getId(), friendId);
        if (friend == null || friendship == null || friendship.getAccepted_at() == null) { loadFriendsMainPage(returnPage); return; }
        Timestamp lastLobbyActivity = LobbyRepository.getLastLobbyActivity(friendId);
        String passiveGames = SearchProfileRepository.getForUser(friendId).stream()
                .filter(profile -> profile.passiveEnabled())
                .map(profile -> gameDisplayName(profile.gameId()))
                .distinct()
                .reduce((first, next) -> first + "\n• " + next)
                .map(names -> "• " + names)
                .orElse(LanguageManager.getMessageForUser("UserProfile.Friends.Profile.None", userObject.getId()));
        HashMap<String, String> replacements = new HashMap<>();
        replacements.put("%username%", friend.getUsername());
        replacements.put("%userId%", String.valueOf(friend.getId()));
        replacements.put("%createdAt%", "<t:" + friend.getCreatedAt().getTime() / 1000 + ":R>");
        replacements.put("%friendsSince%", discordTime(friendship.getAccepted_at()));
        replacements.put("%lastPlayed%", lastLobbyActivity == null
                ? LanguageManager.getMessageForUser("UserProfile.Friends.Profile.Never", userObject.getId())
                : discordTime(lastLobbyActivity));
        replacements.put("%rating%", ratingLabel(friendId));
        replacements.put("%passiveGames%", passiveGames);
        String title = LanguageManager.getMessageForUser(
                "UserProfile.Friends.Profile.title", userObject.getId(), replacements);
        String description = LanguageManager.getMessageForUser(
                "UserProfile.Friends.Profile.description", userObject.getId(), replacements);
        message.editMessageEmbeds(new EmbedCreator().setTitle(title).setDescription(description).build())
                .setComponents(ActionRow.of(
                        Button.danger("friendRemove-" + friendId + "-" + returnPage,
                                LanguageManager.getMessageForUser("UserProfile.Friends.Profile.Remove", userObject.getId())),
                        Button.primary("friendsPage-" + returnPage,
                                LanguageManager.getMessageForUser("UserProfile.Button.Back", userObject.getId())))).queue();
    }

    public void loadPassivePage() {
        loadPassivePage(0);
    }

    public void loadPassivePage(int requestedPage) {
        PassiveQueueSettings settings = PassiveQueueSettingsRepository.get(userObject.getId());
        List<GameProfile> gameProfiles = GameProfileRepository.getForUser(userObject.getId());
        int pages = Math.max(1, (gameProfiles.size() + 22) / 23);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int from = Math.min(page * 23, gameProfiles.size());
        int to = Math.min(from + 23, gameProfiles.size());
        HashMap<String, String> replacements = new HashMap<>();
        replacements.put("%enabled%", LanguageManager.getMessageForUser(settings.enabled() ? "General.On" : "General.Off", userObject.getId()));
        replacements.put("%sync%", LanguageManager.getMessageForUser(settings.syncOnlineStatus() ? "General.On" : "General.Off", userObject.getId()));
        replacements.put("%profiles%", String.valueOf(SearchProfileRepository.getForUser(userObject.getId()).size()));
        List<ActionRow> rows = new ArrayList<>();
        if (from < to) {
            List<SelectOption> options = gameProfiles.subList(from, to).stream().map(profile -> {
                var search = SearchProfileRepository.get(userObject.getId(), profile.gameId(), profile.platform());
                String state = t(search != null && search.passiveEnabled() ? "General.On" : "General.Off");
                String label = gameDisplayName(profile.gameId()) + " · " + profile.platform() + " · " + state;
                return SelectOption.of(label.length() > 100 ? label.substring(0, 100) : label,
                        profile.gameId() + "|" + profile.platform());
            }).toList();
            rows.add(ActionRow.of(StringSelectMenu.create("passiveProfileToggle-" + page)
                    .setPlaceholder(t("PassiveQ.Profile.Select")).addOptions(options).build()));
        }
        if (pages > 1) rows.add(ActionRow.of(
                Button.secondary("passivePage-" + Math.max(0, page - 1), t("General.Previous")).withDisabled(page == 0),
                Button.secondary("passivePage-" + Math.min(pages - 1, page + 1), t("General.Next")).withDisabled(page >= pages - 1)));
        rows.add(ActionRow.of(Button.primary("passiveConfigure", LanguageManager.getMessageForUser("PassiveQ.Button.Configure", userObject.getId()))));
        rows.add(ActionRow.of(Button.secondary("passiveGlobalToggle", LanguageManager.getMessageForUser(
                                        settings.enabled() ? "PassiveQ.Button.Disable" : "PassiveQ.Button.Enable", userObject.getId())),
                                Button.secondary("passiveSyncToggle", LanguageManager.getMessageForUser(
                                        settings.syncOnlineStatus() ? "PassiveQ.Button.DisableSync" : "PassiveQ.Button.EnableSync", userObject.getId()))));
        rows.add(ActionRow.of(Button.primary("mainPage", LanguageManager.getMessageForUser("UserProfile.Button.Back", userObject.getId()))));
        message.editMessageEmbeds(LanguageManager.getEmbedForUser("PassiveQ", userObject.getId(), replacements).build())
                .setComponents(rows).queue();
    }

    public void loadPartyPage(int requestedPage) {
        loadPartyPage(requestedPage, null);
    }

    public void loadPartyPage(int requestedPage, String noticeKey) {
        PartyObject loadedParty = PartyRepository.getForUser(userObject.getId());
        if (loadedParty == null) {
            loadedParty = PartyRepository.create(userObject.getId());
            if (loadedParty != null) ManagementMessageUpdater.refreshFriendActivity(userObject.getId());
        }
        if (loadedParty == null) { loadStartPage(); return; }
        final PartyObject party = loadedParty;
        boolean host = party.hostUserId() == userObject.getId();
        String members = party.memberIds().stream().map(UserController::get).filter(member -> member != null)
                .map(member -> (member.getId() == party.hostUserId() ? "👑 " : "") + member.getUsername())
                .reduce((a, b) -> a + "\n" + b).orElse("-");
        HashMap<String, String> replacements = new HashMap<>();
        replacements.put("%members%", members);
        replacements.put("%count%", String.valueOf(party.memberIds().size()));
        replacements.put("%notice%", noticeKey == null ? "" : "\n\n" +
                LanguageManager.getMessageForUser(noticeKey, userObject.getId()));
        List<ActionRow> rows = new ArrayList<>();
        if (host) {
            List<Integer> available = FriendRepository.getAcceptedFriendIds(userObject.getId()).stream()
                    .filter(friendId -> !party.memberIds().contains(friendId))
                    .filter(friendId -> PartyRepository.getForUser(friendId) == null).toList();
            int pages = Math.max(1, (available.size() + 22) / 23);
            int page = Math.max(0, Math.min(requestedPage, pages - 1));
            int from = Math.min(page * 23, available.size()); int to = Math.min(from + 23, available.size());
            if (from < to) {
                List<SelectOption> options = available.subList(from, to).stream().map(UserController::get)
                        .filter(friend -> friend != null).map(friend -> SelectOption.of(friend.getUsername(), String.valueOf(friend.getId()))).toList();
                rows.add(ActionRow.of(StringSelectMenu.create("partyFriendSelect-" + page)
                        .setPlaceholder(LanguageManager.getMessageForUser("Party.Invite.Dropdown", userObject.getId()))
                        .addOptions(options).build()));
            }
            if (pages > 1) rows.add(ActionRow.of(
                    Button.secondary("partyPage-" + Math.max(0, page - 1), LanguageManager.getMessageForUser("General.Previous", userObject.getId())).withDisabled(page == 0),
                    Button.secondary("partyPage-" + Math.min(pages - 1, page + 1), LanguageManager.getMessageForUser("General.Next", userObject.getId())).withDisabled(page >= pages - 1)));
            List<SelectOption> kickable = party.memberIds().stream().filter(memberId -> memberId != userObject.getId())
                    .map(UserController::get).filter(Objects::nonNull)
                    .map(member -> SelectOption.of(member.getUsername(), String.valueOf(member.getId()))).toList();
            if (!kickable.isEmpty()) rows.add(ActionRow.of(StringSelectMenu.create("partyKickMember")
                    .setPlaceholder(t("Party.Kick.Select")).addOptions(kickable).build()));
        }
        rows.add(ActionRow.of(Button.danger("partyLeave", LanguageManager.getMessageForUser("Party.Button.Leave", userObject.getId())),
                Button.primary("mainPage", LanguageManager.getMessageForUser("UserProfile.Button.Back", userObject.getId()))));
        message.editMessageEmbeds(LanguageManager.getEmbedForUser("Party.Manage", userObject.getId(), replacements).build())
                .setComponents(rows).queue();
    }

    private int countActiveFriends() {
        List<Integer> friends = FriendRepository.getAcceptedFriendIds(userObject.getId());
        Set<Integer> active = new HashSet<>(FriendRepository.getPartyOrLobbyActiveFriendIds(userObject.getId()));
        if (Main.lobbyService == null) return active.size();
        for (int friendId : friends) {
            boolean hasPassiveProfile = SearchProfileRepository.getForUser(friendId).stream()
                    .anyMatch(profile -> profile.passiveEnabled());
            if (hasPassiveProfile && Main.lobbyService.isPassiveQueueAvailable(friendId)) active.add(friendId);
        }
        return active.size();
    }

    private String ratingLabel(int userId) {
        Double rating = ReviewRepository.averageRating(userId);
        return rating == null ? t("Rating.None")
                : RatingFormatter.stars(rating);
    }

    private static String discordTime(Timestamp timestamp) {
        return "<t:" + timestamp.getTime() / 1000 + ":R>";
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static String gameDisplayName(int gameId) {
        GameObject game = GameRepository.get(gameId);
        if (game == null) return "#" + gameId;
        GameObject parent = game.getSubGameFrom();
        return parent == null ? game.getName() : parent.getName() + " · " + game.getName();
    }

    private String compatibleRankLabel(LobbyObject lobby) {
        if (lobby.isRankRulesUnrestricted()) return t("Lobby.View.AnyRank");
        List<Integer> memberRanks = new ArrayList<>();
        for (int memberId : LobbyRepository.memberIds(lobby.getId())) {
            GameProfile profile = GameProfileRepository.getForGame(memberId, lobby.getGameID()).stream()
                    .filter(candidate -> candidate.platform().equalsIgnoreCase(lobby.getPlatform())
                            || candidate.platform().equalsIgnoreCase("ANY") || lobby.getPlatform().equalsIgnoreCase("ANY"))
                    .findFirst().orElse(null);
            if (profile != null) memberRanks.add(profile.rankValue());
        }
        List<GameOption> ranks = RankCompatibilityRepository.getCompatibleRanksForSources(lobby.getGameID(), memberRanks);
        if (lobby.getCustomRankMin() != null)
            ranks = ranks.stream().filter(rank -> rank.sortOrder() >= lobby.getCustomRankMin()).toList();
        if (lobby.getCustomRankMax() != null)
            ranks = ranks.stream().filter(rank -> rank.sortOrder() <= lobby.getCustomRankMax()).toList();
        if (ranks.isEmpty()) return rankName(lobby.getGameID(), lobby.getRankMin());
        return RankDisplayFormatter.format(ranks);
    }

    private String lobbyPlayerList(LobbyObject lobby) {
        GameObject game = GameRepository.get(lobby.getGameID());
        boolean skillbased = game != null && game.isSkillbased();
        String players = LobbyRepository.memberIds(lobby.getId()).stream().map(UserController::get)
                .filter(Objects::nonNull).map(member -> {
                    String rating = ratingLabel(member.getId());
                    if (!skillbased) return "• " + member.getUsername() + " · ⭐ " + rating;
                    GameProfile profile = GameProfileRepository.getForGame(member.getId(), lobby.getGameID()).stream()
                            .filter(candidate -> candidate.platform().equalsIgnoreCase(lobby.getPlatform())
                                    || candidate.platform().equalsIgnoreCase("ANY")
                                    || lobby.getPlatform().equalsIgnoreCase("ANY"))
                            .findFirst().orElse(null);
                    String rank = profile == null ? t("Lobby.Invitation.RankUnknown")
                            : rankName(lobby.getGameID(), profile.rankValue());
                    return "• " + member.getUsername() + " · " + rank + " · ⭐ " + rating;
                }).reduce((first, next) -> first + "\n" + next).orElse("-");
        return players.length() > 2500 ? players.substring(0, 2497) + "..." : players;
    }

    private static String rankName(int gameId, int rankOrder) {
        return GameOptionRepository.get(gameId, GameOption.Type.RANK).stream()
                .filter(rank -> rank.sortOrder() == rankOrder).map(GameOption::name).findFirst()
                .orElse(rankOrder == 0 ? "-" : String.valueOf(rankOrder));
    }

    private String t(String key) {
        return LanguageManager.getMessageForUser(key, userObject.getId());
    }

    private String t(String key, Map<String, String> replacements) {
        return LanguageManager.getMessageForUser(key, userObject.getId(), replacements);
    }

}
