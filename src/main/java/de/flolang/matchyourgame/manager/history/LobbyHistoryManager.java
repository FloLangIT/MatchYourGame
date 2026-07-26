package de.flolang.matchyourgame.manager.history;

import de.flolang.matchyourgame.database.block.BlockRepository;
import de.flolang.matchyourgame.database.friend.FriendObject;
import de.flolang.matchyourgame.database.friend.FriendRepository;
import de.flolang.matchyourgame.database.game.GameController;
import de.flolang.matchyourgame.database.game.GameObject;
import de.flolang.matchyourgame.database.history.LobbyHistoryRepository;
import de.flolang.matchyourgame.database.lobby.LobbyObject;
import de.flolang.matchyourgame.database.lobby.LobbyRepository;
import de.flolang.matchyourgame.database.match.MatchRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class LobbyHistoryManager {
    private static final int PAGE_SIZE = 10;
    private static final int FILTER_PAGE_SIZE = 24;

    private final Message message;
    private final UserObject user;

    public LobbyHistoryManager(Message message, UserObject user) {
        this.message = message;
        this.user = user;
    }

    public void loadGames(int requestedPage) {
        List<LobbyHistoryRepository.GameHistory> games = LobbyHistoryRepository.gamesForUser(user.getId());
        int pages = pages(games.size(), 23);
        int page = clamp(requestedPage, pages);
        int from = Math.min(page * 23, games.size());
        int to = Math.min(from + 23, games.size());
        List<LobbyHistoryRepository.GameHistory> shown = games.subList(from, to);
        StringBuilder description = new StringBuilder(t("History.Games.Description"));
        for (LobbyHistoryRepository.GameHistory game : shown) {
            description.append("\n\n**").append(game.gameName()).append("**")
                    .append("\n").append(t("History.Games.Entry", Map.of(
                            "%lobbies%", String.valueOf(game.lobbyCount()),
                            "%matches%", String.valueOf(game.matchCount()))));
        }
        if (shown.isEmpty()) description.append("\n\n").append(t("History.Games.Empty"));
        List<ActionRow> rows = new ArrayList<>();
        if (!shown.isEmpty()) rows.add(ActionRow.of(StringSelectMenu.create("historyGameSelect")
                .setPlaceholder(t("History.Games.Select"))
                .addOptions(shown.stream().map(game -> SelectOption.of(game.gameName(),
                        String.valueOf(game.gameId()))).toList()).build()));
        if (pages > 1) rows.add(ActionRow.of(
                Button.secondary("historyGamesPage-" + Math.max(0, page - 1), t("General.Previous"))
                        .withDisabled(page == 0),
                Button.secondary("historyGamesPage-" + Math.min(pages - 1, page + 1), t("General.Next"))
                        .withDisabled(page >= pages - 1)));
        rows.add(ActionRow.of(Button.primary("mainPage", t("UserProfile.Button.Back"))));
        message.editMessageEmbeds(new EmbedCreator().setTitle(t("History.Games.Title"))
                        .setDescription(description.toString()).build())
                .setComponents(rows).queue();
    }

    public void loadLobbies(int gameId, Integer teammateId, int requestedHistoryPage,
                            int requestedFilterPage) {
        GameObject game = GameController.get(gameId);
        if (game == null) {
            loadGames(0);
            return;
        }
        List<LobbyHistoryRepository.HistoryMember> teammates =
                LobbyHistoryRepository.teammatesForGame(user.getId(), gameId);
        Integer selectedTeammateId = teammateId != null
                && teammates.stream().anyMatch(member -> member.userId() == teammateId) ? teammateId : null;
        int filterPages = pages(teammates.size(), FILTER_PAGE_SIZE);
        int filterPage = clamp(requestedFilterPage, filterPages);
        int total = LobbyHistoryRepository.lobbyCount(user.getId(), gameId, selectedTeammateId);
        int historyPages = pages(total, PAGE_SIZE);
        int historyPage = clamp(requestedHistoryPage, historyPages);
        List<LobbyHistoryRepository.LobbyHistory> lobbies = LobbyHistoryRepository.lobbiesForUser(
                user.getId(), gameId, selectedTeammateId, historyPage * PAGE_SIZE, PAGE_SIZE);

        String filterName = selectedTeammateId == null ? t("History.Filter.All")
                : teammates.stream().filter(member -> member.userId() == selectedTeammateId)
                .map(LobbyHistoryRepository.HistoryMember::username).findFirst().orElse("-");
        StringBuilder description = new StringBuilder(t("History.Lobbies.Description", Map.of(
                "%game%", game.getName(), "%filter%", filterName)));
        for (LobbyHistoryRepository.LobbyHistory lobby : lobbies) {
            description.append("\n\n**#").append(lobby.lobbyId()).append(" · ")
                    .append(timestamp(lobby.closedAt())).append("**")
                    .append("\n").append(t("History.Lobbies.Entry", Map.of(
                            "%matches%", String.valueOf(lobby.matchCount()),
                            "%platform%", lobby.platform(),
                            "%region%", lobby.region())));
        }
        if (lobbies.isEmpty()) description.append("\n\n").append(t("History.Lobbies.Empty"));

        List<ActionRow> rows = new ArrayList<>();
        if (!lobbies.isEmpty()) rows.add(ActionRow.of(StringSelectMenu.create(
                        "historyLobbySelect-" + gameId + "-" + value(selectedTeammateId) + "-"
                                + historyPage + "-" + filterPage)
                .setPlaceholder(t("History.Lobbies.Select"))
                .addOptions(lobbies.stream().map(lobby -> SelectOption.of(
                        "#" + lobby.lobbyId() + " · " + lobby.matchCount() + " "
                                + t(lobby.matchCount() == 1 ? "History.Match.One" : "History.Match.Other"),
                        String.valueOf(lobby.lobbyId()))).toList()).build()));

        int filterFrom = Math.min(filterPage * FILTER_PAGE_SIZE, teammates.size());
        int filterTo = Math.min(filterFrom + FILTER_PAGE_SIZE, teammates.size());
        List<SelectOption> filterOptions = new ArrayList<>();
        filterOptions.add(SelectOption.of(t("History.Filter.All"), "0")
                .withDefault(selectedTeammateId == null));
        teammates.subList(filterFrom, filterTo).forEach(member -> filterOptions.add(SelectOption.of(
                trim(member.username(), 80), String.valueOf(member.userId()))
                .withDescription(trim(t("History.Filter.Shared", Map.of(
                        "%count%", String.valueOf(member.sharedLobbies()))), 100))
                .withDefault(selectedTeammateId != null && selectedTeammateId == member.userId())));
        rows.add(ActionRow.of(StringSelectMenu.create(
                        "historyTeammateSelect-" + gameId + "-" + historyPage + "-" + filterPage)
                .setPlaceholder(t("History.Filter.Select")).addOptions(filterOptions).build()));
        if (historyPages > 1) rows.add(ActionRow.of(
                Button.secondary(historyPageId(gameId, selectedTeammateId, historyPage - 1, filterPage),
                                t("General.Previous")).withDisabled(historyPage == 0),
                Button.secondary(historyPageId(gameId, selectedTeammateId, historyPage + 1, filterPage),
                                t("General.Next")).withDisabled(historyPage >= historyPages - 1)));
        if (filterPages > 1) rows.add(ActionRow.of(
                Button.secondary(historyPageId(gameId, selectedTeammateId, historyPage, filterPage - 1),
                                t("History.Filter.Previous")).withDisabled(filterPage == 0),
                Button.secondary(historyPageId(gameId, selectedTeammateId, historyPage, filterPage + 1),
                                t("History.Filter.Next")).withDisabled(filterPage >= filterPages - 1)));
        rows.add(ActionRow.of(Button.primary("historyGamesPage-0", t("UserProfile.Button.Back"))));
        message.editMessageEmbeds(new EmbedCreator().setTitle(t("History.Lobbies.Title", Map.of(
                        "%game%", game.getName()))).setDescription(limit(description.toString(), 4000)).build())
                .setComponents(rows).queue();
    }

    public void loadLobby(int lobbyId, int gameId, Integer teammateId, int historyPage, int filterPage) {
        loadLobby(lobbyId, gameId, teammateId, historyPage, filterPage, 0);
    }

    public void loadLobby(int lobbyId, int gameId, Integer teammateId, int historyPage, int filterPage,
                          int requestedMemberPage) {
        loadLobby(lobbyId, gameId, teammateId, historyPage, filterPage, requestedMemberPage, 0);
    }

    public void loadLobby(int lobbyId, int gameId, Integer teammateId, int historyPage, int filterPage,
                          int requestedMemberPage, int requestedMatchPage) {
        if (!LobbyHistoryRepository.mayAccessLobby(user.getId(), lobbyId)) {
            loadLobbies(gameId, teammateId, historyPage, filterPage);
            return;
        }
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null || lobby.getGameID() != gameId) {
            loadLobbies(gameId, teammateId, historyPage, filterPage);
            return;
        }
        List<MatchRepository.MatchInfo> matches = MatchRepository.getForLobby(lobbyId);
        StringBuilder description = new StringBuilder(t("History.Lobby.Description", Map.of(
                "%lobby%", String.valueOf(lobbyId),
                "%game%", gameName(gameId),
                "%created%", timestamp(lobby.getCreatedAt()),
                "%closed%", timestamp(lobby.getClosedAt()),
                "%platform%", lobby.getPlatform(),
                "%region%", lobby.getRegion())));
        List<ActionRow> rows = new ArrayList<>();
        if (matches.isEmpty()) description.append("\n\n").append(t("History.Lobby.NoMatches"));
        int matchPages = pages(matches.size(), 10);
        int matchPage = clamp(requestedMatchPage, matchPages);
        int matchFrom = Math.min(matchPage * 10, matches.size());
        int matchTo = Math.min(matchFrom + 10, matches.size());
        List<MatchRepository.MatchInfo> displayedMatches = matches.subList(matchFrom, matchTo);
        for (MatchRepository.MatchInfo match : displayedMatches) {
            description.append("\n\n**").append(t("History.Lobby.MatchTitle", Map.of(
                    "%number%", String.valueOf(match.sequenceNumber())))).append("**")
                    .append("\n").append(t("History.Lobby.MatchStatus", Map.of("%status%", match.status())));
        }
        if (!displayedMatches.isEmpty()) rows.add(ActionRow.of(StringSelectMenu.create(
                        "historyMatchSelect-" + lobbyId + "-" + gameId + "-" + value(teammateId)
                                + "-" + historyPage + "-" + filterPage + "-" + requestedMemberPage
                                + "-" + matchPage)
                .setPlaceholder(t("History.Lobby.MatchSelect"))
                .addOptions(displayedMatches.stream().map(match -> SelectOption.of(
                        t("History.Lobby.MatchTitle", Map.of(
                                "%number%", String.valueOf(match.sequenceNumber()))), String.valueOf(match.id()))
                        .withDescription(trim(match.status(), 100))).toList()).build()));
        if (matchPages > 1) rows.add(ActionRow.of(
                Button.secondary("historyMatchesPage-" + lobbyId + "-" + gameId + "-" + value(teammateId)
                                + "-" + historyPage + "-" + filterPage + "-" + requestedMemberPage + "-"
                                + Math.max(0, matchPage - 1),
                        t("General.Previous")).withDisabled(matchPage == 0),
                Button.secondary("historyMatchesPage-" + lobbyId + "-" + gameId + "-" + value(teammateId)
                                + "-" + historyPage + "-" + filterPage + "-" + requestedMemberPage + "-"
                                + Math.min(matchPages - 1, matchPage + 1),
                        t("General.Next")).withDisabled(matchPage >= matchPages - 1)));
        List<Integer> memberIds = LobbyRepository.allMemberIds(lobbyId).stream()
                .filter(memberId -> memberId != user.getId()).toList();
        int memberPages = pages(memberIds.size(), 25);
        int memberPage = clamp(requestedMemberPage, memberPages);
        int memberFrom = Math.min(memberPage * 25, memberIds.size());
        int memberTo = Math.min(memberFrom + 25, memberIds.size());
        List<SelectOption> members = memberIds.subList(memberFrom, memberTo).stream().map(UserController::get)
                .filter(java.util.Objects::nonNull).limit(25)
                .map(member -> SelectOption.of(trim(member.getUsername(), 80), String.valueOf(member.getId()))).toList();
        if (!members.isEmpty()) rows.add(ActionRow.of(StringSelectMenu.create(
                        "historyMemberSelect-" + lobbyId + "-" + gameId + "-" + value(teammateId)
                                + "-" + historyPage + "-" + filterPage + "-" + memberPage)
                .setPlaceholder(t("History.Member.Select")).addOptions(members).build()));
        if (memberPages > 1) rows.add(ActionRow.of(
                Button.secondary("historyMembersPage-" + lobbyId + "-" + gameId + "-" + value(teammateId)
                                + "-" + historyPage + "-" + filterPage + "-" + Math.max(0, memberPage - 1),
                        t("General.Previous")).withDisabled(memberPage == 0),
                Button.secondary("historyMembersPage-" + lobbyId + "-" + gameId + "-" + value(teammateId)
                                + "-" + historyPage + "-" + filterPage + "-"
                                + Math.min(memberPages - 1, memberPage + 1),
                        t("General.Next")).withDisabled(memberPage >= memberPages - 1)));
        rows.add(ActionRow.of(Button.primary(historyPageId(gameId, teammateId, historyPage, filterPage),
                t("UserProfile.Button.Back"))));
        message.editMessageEmbeds(new EmbedCreator().setTitle(t("History.Lobby.Title", Map.of(
                        "%lobby%", String.valueOf(lobbyId))))
                .setDescription(limit(description.toString(), 4000)).build()).setComponents(rows).queue();
    }

    public void loadMatch(int lobbyId, int matchId, int gameId, Integer teammateId,
                          int historyPage, int filterPage, int memberPage, int matchPage) {
        if (!LobbyHistoryRepository.mayAccessLobby(user.getId(), lobbyId)) return;
        MatchRepository.MatchInfo match = MatchRepository.get(matchId);
        if (match == null || match.lobbyId() != lobbyId) {
            loadLobby(lobbyId, gameId, teammateId, historyPage, filterPage, memberPage, matchPage);
            return;
        }
        StringBuilder description = new StringBuilder(t("History.MatchDetail.Description", Map.of(
                "%lobby%", String.valueOf(lobbyId),
                "%number%", String.valueOf(match.sequenceNumber()),
                "%status%", match.status())));
        List<MatchRepository.MatchStatValue> values = MatchRepository.getValues(match.id());
        if (values.isEmpty()) description.append("\n\n").append(t("History.MatchDetail.NoValues"));
        for (MatchRepository.MatchStatValue stat : values) {
            String owner = stat.username() != null ? stat.username()
                    : stat.teamName() != null ? stat.teamName() : t("History.Lobby.General");
            description.append("\n• ").append(owner).append(" · ").append(stat.name())
                    .append(": **").append(stat.value()).append("**");
        }
        String back = "historyMatchesPage-" + lobbyId + "-" + gameId + "-" + value(teammateId)
                + "-" + historyPage + "-" + filterPage + "-" + memberPage + "-" + matchPage;
        message.editMessageEmbeds(new EmbedCreator().setTitle(t("History.MatchDetail.Title", Map.of(
                        "%number%", String.valueOf(match.sequenceNumber()))))
                .setDescription(limit(description.toString(), 4000)).build())
                .setComponents(ActionRow.of(Button.primary(back, t("UserProfile.Button.Back")))).queue();
    }

    public void loadCurrentMembers(int lobbyId, int requestedPage) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null || !LobbyRepository.memberIds(lobbyId).contains(user.getId())) {
            loadCurrentLobby(lobbyId);
            return;
        }
        List<Integer> memberIds = LobbyRepository.memberIds(lobbyId).stream()
                .filter(memberId -> memberId != user.getId()).toList();
        int pages = pages(memberIds.size(), 25);
        int page = clamp(requestedPage, pages);
        int from = Math.min(page * 25, memberIds.size());
        int to = Math.min(from + 25, memberIds.size());
        List<SelectOption> options = memberIds.subList(from, to).stream().map(UserController::get)
                .filter(java.util.Objects::nonNull)
                .map(member -> SelectOption.of(trim(member.getUsername(), 80),
                        String.valueOf(member.getId()))).toList();
        List<ActionRow> rows = new ArrayList<>();
        if (!options.isEmpty()) rows.add(ActionRow.of(StringSelectMenu.create(
                        "lobbyMemberActionSelect-" + lobbyId)
                .setPlaceholder(t("History.Member.Select")).addOptions(options).build()));
        if (pages > 1) rows.add(ActionRow.of(
                Button.secondary("lobbyMemberActions-" + lobbyId + "-" + Math.max(0, page - 1),
                        t("General.Previous")).withDisabled(page == 0),
                Button.secondary("lobbyMemberActions-" + lobbyId + "-" + Math.min(pages - 1, page + 1),
                        t("General.Next")).withDisabled(page >= pages - 1)));
        rows.add(ActionRow.of(Button.primary("historyCurrentLobbyBack-" + lobbyId,
                t("UserProfile.Button.Back"))));
        message.editMessageEmbeds(new EmbedCreator().setTitle(t("History.CurrentMembers.Title"))
                        .setDescription(t("History.CurrentMembers.Description", Map.of(
                                "%lobby%", String.valueOf(lobbyId),
                                "%page%", String.valueOf(page + 1),
                                "%pages%", String.valueOf(pages)))).build())
                .setComponents(rows).queue();
    }

    public void loadMember(int contextLobbyId, int targetId, boolean returnToCurrentLobby,
                           int gameId, Integer teammateId, int historyPage, int filterPage) {
        if (!LobbyHistoryRepository.sharedLobby(user.getId(), targetId, contextLobbyId)) {
            if (returnToCurrentLobby) loadCurrentLobby(contextLobbyId);
            else loadLobby(contextLobbyId, gameId, teammateId, historyPage, filterPage);
            return;
        }
        UserObject target = UserController.get(targetId);
        if (target == null) return;
        FriendObject friendship = FriendRepository.get(user.getId(), targetId);
        LobbyObject activeLobby = LobbyRepository.getActiveForUser(user.getId());
        boolean canInvite = activeLobby != null && activeLobby.getLeaderID() == user.getId()
                && activeLobby.isOpen() && !LobbyRepository.memberIds(activeLobby.getId()).contains(targetId);
        canInvite = canInvite && LobbyRepository.getActiveForUser(targetId) == null;
        String blockStatus = BlockRepository.isActive(user.getId(), targetId)
                ? t("History.Member.BlockActive") : t("History.Member.NotBlocked");
        List<ActionRow> rows = new ArrayList<>();
        rows.add(ActionRow.of(
                Button.success("historyFriend-" + contextLobbyId + "-" + targetId,
                                t(friendship == null ? "History.Member.Friend" : "History.Member.FriendExists"))
                        .withDisabled(friendship != null),
                Button.primary("historyInvite-" + contextLobbyId + "-" + targetId,
                                t("History.Member.Invite"))
                        .withDisabled(!canInvite)));
        rows.add(ActionRow.of(Button.danger(
                        "historyBlockAsk-" + contextLobbyId + "-" + targetId + "-"
                                + (returnToCurrentLobby ? 1 : 0) + "-" + gameId + "-" + value(teammateId)
                                + "-" + historyPage + "-" + filterPage,
                        t("History.Member.Block"))));
        LobbyObject contextLobby = LobbyRepository.get(contextLobbyId);
        if (returnToCurrentLobby && contextLobby != null && contextLobby.getLeaderID() == user.getId())
            rows.add(ActionRow.of(Button.danger("historyKick-" + contextLobbyId + "-" + targetId,
                    t("Lobby.Kick.Select"))));
        String backId = returnToCurrentLobby ? "historyCurrentLobbyBack-" + contextLobbyId
                : "historyLobbyBack-" + contextLobbyId + "-" + gameId + "-" + value(teammateId)
                + "-" + historyPage + "-" + filterPage;
        rows.add(ActionRow.of(Button.primary(backId, t("UserProfile.Button.Back"))));
        message.editMessageEmbeds(new EmbedCreator().setTitle(t("History.Member.Title", Map.of(
                        "%username%", target.getUsername())))
                .setDescription(t("History.Member.Description", Map.of(
                        "%username%", target.getUsername(), "%blockStatus%", blockStatus))).build())
                .setComponents(rows).queue();
    }

    public void loadBlockConfirmation(int contextLobbyId, int targetId, boolean returnToCurrentLobby,
                                      int gameId, Integer teammateId, int historyPage, int filterPage) {
        if (!LobbyHistoryRepository.sharedLobby(user.getId(), targetId, contextLobbyId)) return;
        UserObject target = UserController.get(targetId);
        if (target == null) return;
        String suffix = contextLobbyId + "-" + targetId + "-" + (returnToCurrentLobby ? 1 : 0) + "-"
                + gameId + "-" + value(teammateId) + "-" + historyPage + "-" + filterPage;
        message.editMessageEmbeds(new EmbedCreator().setTitle(t("History.Block.Title"))
                        .setDescription(t("History.Block.Description", Map.of(
                                "%username%", target.getUsername()))).build())
                .setComponents(ActionRow.of(
                        Button.danger("historyBlockConfirm-" + suffix, t("History.Block.Confirm")),
                        Button.secondary("historyBlockCancel-" + suffix, t("History.Block.Cancel"))))
                .queue();
    }

    private void loadCurrentLobby(int lobbyId) {
        new de.flolang.matchyourgame.manager.UserControlManager(message, user)
                .loadLobbyPage(LobbyRepository.get(lobbyId));
    }

    private String historyPageId(int gameId, Integer teammateId, int historyPage, int filterPage) {
        return "historyLobbiesPage-" + gameId + "-" + value(teammateId) + "-"
                + Math.max(0, historyPage) + "-" + Math.max(0, filterPage);
    }

    private String gameName(int gameId) {
        GameObject game = GameController.get(gameId);
        return game == null ? "#" + gameId : game.getName();
    }

    private String t(String key) {
        return LanguageManager.getMessageForUser(key, user.getId());
    }

    private String t(String key, Map<String, String> replacements) {
        return LanguageManager.getMessageForUser(key, user.getId(), replacements);
    }

    private static int pages(int size, int pageSize) {
        return Math.max(1, (size + pageSize - 1) / pageSize);
    }

    private static int clamp(int page, int pages) {
        return Math.max(0, Math.min(page, pages - 1));
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static String timestamp(java.sql.Timestamp timestamp) {
        return timestamp == null ? "-" : "<t:" + timestamp.getTime() / 1000 + ":f>";
    }

    private static String trim(String value, int maxLength) {
        if (value == null) return "-";
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }

    private static String limit(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }
}
