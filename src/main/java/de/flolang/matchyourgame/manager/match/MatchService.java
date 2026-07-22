package de.flolang.matchyourgame.manager.match;

import de.flolang.matchyourgame.database.game.GameStatDefinition;
import de.flolang.matchyourgame.database.game.GameStatRepository;
import de.flolang.matchyourgame.database.lobby.*;
import de.flolang.matchyourgame.database.match.MatchRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.InboxMessageRepository;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.InboxService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MatchService {
    private final JDA jda;
    private final Map<Integer, EntrySession> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, CorrectionSession> corrections = new ConcurrentHashMap<>();

    public MatchService(JDA jda) { this.jda = jda; }

    public EntrySession start(int lobbyId, int hostId) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null || lobby.getLeaderID() != hostId) return null;
        boolean active = List.of(LobbyStatus.FORMING, LobbyStatus.READY, LobbyStatus.ACTIVE).contains(lobby.getStatus());
        boolean closedEntry = false;
        if (lobby.getStatus() == LobbyStatus.CLOSED) {
            MatchRepository.MatchReview review = MatchRepository.getReview(lobbyId);
            if (review == null) MatchRepository.openEntryWindow(lobbyId);
            else if ("COMPLETE".equals(review.status()) && !review.entryFinishedManually())
                MatchRepository.recoverAutomaticallyClosedEntryWindow(lobbyId);
            closedEntry = MatchRepository.canAddMatch(lobbyId);
        }
        if (!active && !closedEntry) return null;
        List<PlayerTarget> candidates = LobbyRepository.allMemberIds(lobbyId).stream()
                .map(UserController::get).filter(Objects::nonNull)
                .map(member -> new PlayerTarget(member.getId(), member.getUsername())).toList();
        if (candidates.size() < 2) return null;
        int matchId = MatchRepository.create(lobbyId);
        if (matchId == 0) return null;
        int teamId = MatchRepository.addTeam(matchId, "Lobby");
        if (teamId == 0) return null;
        EntrySession session = new EntrySession(matchId, lobbyId, hostId, teamId, candidates);
        sessions.put(matchId, session);
        return session;
    }

    public boolean selectParticipants(int matchId, int hostId, List<Integer> selectedIds) {
        EntrySession session = getSession(matchId, hostId);
        if (session == null || session.participantsSelected() || selectedIds.size() < 2) return false;
        LinkedHashSet<Integer> uniqueIds = new LinkedHashSet<>(selectedIds);
        Map<Integer, PlayerTarget> candidates = session.candidates().stream()
                .collect(java.util.stream.Collectors.toMap(PlayerTarget::userId, player -> player));
        if (uniqueIds.size() != selectedIds.size() || !candidates.keySet().containsAll(uniqueIds)) return false;
        List<PlayerTarget> players = uniqueIds.stream().map(candidates::get).toList();
        if (!MatchRepository.addParticipants(matchId, session.teamId(), new ArrayList<>(uniqueIds))) return false;
        LobbyObject lobby = LobbyRepository.get(session.lobbyId());
        if (lobby == null) return false;
        List<StatTask> tasks = new ArrayList<>();
        for (GameStatDefinition definition : GameStatRepository.getEffectiveForGame(lobby.getGameID())) {
            if (definition.scope() == GameStatDefinition.Scope.PLAYER) {
                for (PlayerTarget player : players) tasks.add(new StatTask(definition, player.userId(), null,
                        definition.name() + " · " + player.username()));
            } else tasks.add(new StatTask(definition, null, session.teamId(), definition.name()));
        }
        session.selectParticipants(players, tasks);
        if (tasks.isEmpty()) finish(session);
        return true;
    }

    public List<ActionRow> participantSelectionComponents(EntrySession session, int userId, boolean activeLobby) {
        List<SelectOption> options = session.candidates().stream().limit(25)
                .map(player -> SelectOption.of(player.username(), String.valueOf(player.userId()))).toList();
        List<ActionRow> rows = new ArrayList<>();
        rows.add(ActionRow.of(StringSelectMenu.create("matchPlayers-" + session.matchId())
                .setPlaceholder(t(userId, "Match.Participants.Select"))
                .setMinValues(2).setMaxValues(options.size()).addOptions(options).build()));
        if (activeLobby) rows.add(ActionRow.of(Button.secondary("matchBackToLobby-" + session.lobbyId(),
                t(userId, "Match.Button.BackToLobby"))));
        return rows;
    }

    public EntrySession getSession(int matchId, int hostId) {
        EntrySession session = sessions.get(matchId);
        return session != null && session.hostId() == hostId ? session : null;
    }

    public List<StatTask> nextBatch(int matchId, int hostId) {
        EntrySession session = getSession(matchId, hostId);
        if (session == null) return List.of();
        int end = Math.min(session.cursor() + 5, session.tasks().size());
        return session.tasks().subList(session.cursor(), end);
    }

    public boolean submitBatch(int matchId, int hostId, List<String> values) {
        EntrySession session = getSession(matchId, hostId);
        if (session == null) return false;
        List<StatTask> batch = nextBatch(matchId, hostId);
        if (batch.size() != values.size()) return false;
        for (int i = 0; i < batch.size(); i++) {
            StatTask task = batch.get(i); String value = values.get(i).trim();
            if (!valid(task.definition().valueType(), value)) return false;
        }
        for (int i = 0; i < batch.size(); i++) {
            StatTask task = batch.get(i);
            if (!MatchRepository.saveStat(matchId, task.definition().id(), task.userId(), task.teamId(), values.get(i).trim())) return false;
        }
        session.advance(batch.size());
        if (session.complete()) finish(session);
        return true;
    }

    public boolean confirmLobby(int lobbyId, int userId) {
        boolean confirmed = MatchRepository.confirmLobby(lobbyId, userId);
        if (confirmed) updateReviewMessages(lobbyId);
        return confirmed;
    }

    private void finish(EntrySession session) {
        MatchRepository.markReady(session.matchId());
        sessions.remove(session.matchId());
    }

    public void submitLobbyForConfirmation(int lobbyId) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null || lobby.getStatus() != LobbyStatus.CLOSED) return;
        MatchRepository.openEntryWindow(lobbyId);
    }

    public void storeHostEntryMessage(int lobbyId, String messageId) {
        MatchRepository.storeHostMessage(lobbyId, messageId);
    }

    public boolean finishLobbyEntry(int lobbyId, int hostId) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null || lobby.getLeaderID() != hostId || !MatchRepository.beginLobbyReview(lobbyId, true)) return false;
        sendLobbyReview(lobbyId);
        return true;
    }

    public void processDeadlines() {
        for (MatchRepository.MatchReview review : MatchRepository.dueEntryReviews()) {
            if (MatchRepository.beginLobbyReview(review.lobbyId(), false)) {
                deleteHostEntryMessage(review);
                sendLobbyReview(review.lobbyId());
            }
        }
        for (MatchRepository.MatchReview review : MatchRepository.dueConfirmationReviews()) {
            if (MatchRepository.completeLobbyReview(review.lobbyId())) updateReviewMessages(review.lobbyId());
        }
    }

    public CorrectionSession startCorrection(int matchId, int userId) {
        MatchRepository.MatchInfo match = MatchRepository.get(matchId);
        MatchRepository.MatchReview review = match == null ? null : MatchRepository.getReview(match.lobbyId());
        if (match == null || review == null || !"PENDING_CONFIRMATION".equals(match.status())
                || !"REVIEW".equals(review.status()) || review.reviewDeadline() == null
                || !review.reviewDeadline().after(new java.sql.Timestamp(System.currentTimeMillis()))
                || !MatchRepository.isParticipant(matchId, userId) || MatchRepository.isLobbyHost(matchId, userId)) return null;
        List<CorrectionTask> tasks = MatchRepository.getValues(matchId).stream()
                .map(value -> new CorrectionTask(value, valueLabel(value))).toList();
        if (tasks.isEmpty()) return null;
        CorrectionSession session = new CorrectionSession(matchId, userId, tasks);
        corrections.put(matchId, session);
        return session;
    }

    public CorrectionSession getCorrection(int matchId, int userId) {
        CorrectionSession session = corrections.get(matchId);
        return session != null && session.userId() == userId ? session : null;
    }

    public List<CorrectionTask> nextCorrectionBatch(int matchId, int userId) {
        CorrectionSession session = getCorrection(matchId, userId);
        if (session == null) return List.of();
        int end = Math.min(session.cursor() + 5, session.tasks().size());
        return session.tasks().subList(session.cursor(), end);
    }

    public boolean submitCorrectionBatch(int matchId, int userId, List<String> values) {
        CorrectionSession session = getCorrection(matchId, userId);
        if (session == null) return false;
        List<CorrectionTask> batch = nextCorrectionBatch(matchId, userId);
        if (batch.size() != values.size()) return false;
        for (int i = 0; i < batch.size(); i++) {
            GameStatDefinition.ValueType type;
            try { type = GameStatDefinition.ValueType.valueOf(batch.get(i).value().valueType()); }
            catch (IllegalArgumentException e) { return false; }
            if (!valid(type, values.get(i).trim())) return false;
        }
        for (int i = 0; i < batch.size(); i++)
            session.proposedValues().put(batch.get(i).value().id(), values.get(i).trim());
        session.advance(batch.size());
        if (!session.complete()) return true;
        List<MatchRepository.ProposedValue> proposal = session.tasks().stream()
                .map(task -> new MatchRepository.ProposedValue(task.value().id(),
                        session.proposedValues().getOrDefault(task.value().id(), task.value().value()))).toList();
        MatchRepository.MatchInfo match = MatchRepository.get(matchId);
        if (match == null || !MatchRepository.applyCorrection(match.lobbyId(), matchId, userId, proposal)) {
            session.rewind(batch.size());
            return false;
        }
        corrections.remove(matchId);
        updateReviewMessages(match.lobbyId());
        return true;
    }

    public MessageEmbed closureSummary(int lobbyId, int userId) {
        String entries = teamSummary(lobbyId, userId);
        long deadlineEpoch = MatchRepository.entryDeadlineEpochSeconds(lobbyId);
        String deadline = deadlineEpoch == 0 ? "-" : "<t:" + deadlineEpoch + ":R>";
        return new EmbedCreator().setTitle(t(userId, "Match.Entry.Title"))
                .setDescription(t(userId, "Match.Entry.Description", Map.of(
                        "%matches%", entries, "%deadline%", deadline))).build();
    }

    public MessageEmbed activeEntryEmbed(int lobbyId, int userId, String notice) {
        return new EmbedCreator().setTitle(t(userId, "Match.Active.Title"))
                .setDescription(t(userId, "Match.Active.Description", Map.of(
                        "%notice%", notice == null ? "" : notice,
                        "%matches%", teamSummary(lobbyId, userId)))).build();
    }

    public MessageEmbed participantSelectionEmbed(int userId) {
        return new EmbedCreator().setTitle(t(userId, "Match.Participants.Title"))
                .setDescription(t(userId, "Match.Participants.Description")).build();
    }

    public MessageEmbed entryProgressEmbed(int userId, String description) {
        return new EmbedCreator().setTitle(t(userId, "Match.Entry.ProgressTitle"))
                .setDescription(description).build();
    }

    public MessageEmbed playerStatsEmbed(int lobbyId, int matchId, int userId) {
        MatchRepository.MatchInfo match = MatchRepository.get(matchId);
        if (match == null || match.lobbyId() != lobbyId) return null;
        List<MatchRepository.MatchStatValue> values = MatchRepository.getValues(matchId).stream()
                .filter(value -> "PLAYER".equals(value.scope())).toList();
        Map<String, List<MatchRepository.MatchStatValue>> byPlayer = new LinkedHashMap<>();
        values.forEach(value -> byPlayer.computeIfAbsent(value.username() == null ? "-" : value.username(),
                ignored -> new ArrayList<>()).add(value));
        StringBuilder content = new StringBuilder();
        byPlayer.forEach((player, playerValues) -> {
            if (!content.isEmpty()) content.append("\n\n");
            content.append("**").append(player).append("**");
            playerValues.forEach(value -> content.append("\n• ").append(value.name()).append(": **")
                    .append(value.value()).append("**"));
        });
        String results = content.isEmpty() ? t(userId, "Match.Active.NoPlayerValues") : limit(content.toString(), 3800);
        return new EmbedCreator().setTitle(t(userId, "Match.Active.PlayerTitle", Map.of(
                        "%match%", String.valueOf(match.sequenceNumber()))))
                .setDescription(results).build();
    }

    public List<ActionRow> activeEntryComponents(int lobbyId, int userId) {
        return activeEntryComponents(lobbyId, userId, null, null);
    }

    public List<ActionRow> activeEntryComponents(int lobbyId, int userId, Integer matchId, String nextLabel) {
        List<ActionRow> rows = new ArrayList<>();
        if (matchId == null) rows.add(ActionRow.of(
                Button.primary("matchAdd-" + lobbyId, t(userId, "Match.Button.AddAnother")),
                Button.secondary("matchBackToLobby-" + lobbyId, t(userId, "Match.Button.BackToLobby"))));
        else rows.add(ActionRow.of(
                Button.primary("matchNext-" + matchId, nextLabel),
                Button.secondary("matchBackToLobby-" + lobbyId, t(userId, "Match.Button.BackToLobby"))));
        List<MatchRepository.MatchInfo> completed = completedMatches(lobbyId).stream().limit(25).toList();
        if (matchId == null && !completed.isEmpty()) rows.add(ActionRow.of(StringSelectMenu.create("matchPlayerStats-" + lobbyId)
                .setPlaceholder(t(userId, "Match.Active.PlayerSelect"))
                .addOptions(completed.stream().map(match -> SelectOption.of(
                        t(userId, "Match.Review.Match", Map.of("%match%", String.valueOf(match.sequenceNumber()))),
                        String.valueOf(match.id()))).toList()).build()));
        return rows;
    }

    public List<ActionRow> playerStatsComponents(int lobbyId, int userId) {
        return List.of(ActionRow.of(
                Button.secondary("matchEntryOverview-" + lobbyId, t(userId, "Match.Active.BackToOverview")),
                Button.secondary("matchBackToLobby-" + lobbyId, t(userId, "Match.Button.BackToLobby"))));
    }

    private String teamSummary(int lobbyId, int userId) {
        StringBuilder matches = new StringBuilder();
        for (MatchRepository.MatchInfo match : completedMatches(lobbyId)) {
            if (!matches.isEmpty()) matches.append("\n\n");
            matches.append("**#").append(match.sequenceNumber()).append("**");
            List<MatchRepository.MatchStatValue> teamValues = MatchRepository.getValues(match.id()).stream()
                    .filter(value -> "TEAM".equals(value.scope())).toList();
            if (teamValues.isEmpty()) matches.append("\n").append(t(userId, "Match.Summary.NoTeamValues"));
            else teamValues.forEach(value -> matches.append("\n• ").append(value.name()).append(": **")
                    .append(value.value()).append("**"));
        }
        return matches.isEmpty() ? t(userId, "Match.Summary.None") : limit(matches.toString(), 3400);
    }

    private static List<MatchRepository.MatchInfo> completedMatches(int lobbyId) {
        return MatchRepository.getForLobby(lobbyId).stream()
                .filter(match -> !"DRAFT".equals(match.status()) && !"REJECTED".equals(match.status())).toList();
    }

    public List<ActionRow> hostEntryComponents(int lobbyId, int userId) {
        return List.of(ActionRow.of(
                Button.primary("matchAdd-" + lobbyId, t(userId, "Match.Button.AddAnother")),
                Button.success("matchDone-" + lobbyId, t(userId, "Match.Button.Done"))));
    }

    public List<MessageEmbed> reviewEmbeds(int lobbyId, int userId) {
        MatchRepository.MatchReview review = MatchRepository.getReview(lobbyId);
        StringBuilder content = new StringBuilder();
        for (MatchRepository.MatchInfo match : MatchRepository.getForLobby(lobbyId)) {
            if ("DRAFT".equals(match.status()) || "REJECTED".equals(match.status())) continue;
            if (!MatchRepository.isReviewRelevant(match.id(), userId)) continue;
            if (!content.isEmpty()) content.append("\n\n");
            content.append("**#").append(match.sequenceNumber()).append("**");
            List<MatchRepository.MatchStatValue> values = MatchRepository.getValues(match.id());
            if (values.isEmpty()) content.append("\n-");
            else values.forEach(value -> content.append("\n• **").append(valueLabel(value)).append("**: ")
                    .append(value.value()));
        }
        String results = content.isEmpty() ? t(userId, "Match.Summary.None") : content.toString();
        boolean complete = review != null && "COMPLETE".equals(review.status());
        String deadline = review == null || review.reviewDeadline() == null ? "-"
                : "<t:" + review.reviewDeadline().getTime() / 1000 + ":R>";
        List<String> pages = splitPages(results, 3200, 10);
        List<MessageEmbed> embeds = new ArrayList<>();
        String title = t(userId, complete ? "Match.Review.CompleteTitle" : "Match.Review.Title");
        for (int i = 0; i < pages.size(); i++) {
            String pageTitle = pages.size() == 1 ? title : title + " · " + (i + 1) + "/" + pages.size();
            embeds.add(new EmbedCreator().setTitle(pageTitle)
                    .setDescription(t(userId, complete ? "Match.Review.CompleteDescription" : "Match.Review.Description",
                            Map.of("%matches%", pages.get(i), "%deadline%", deadline))).build());
        }
        return embeds;
    }

    public List<ActionRow> reviewComponents(int lobbyId, int userId) {
        MatchRepository.MatchReview review = MatchRepository.getReview(lobbyId);
        if (review == null || !"REVIEW".equals(review.status())) return List.of();
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        boolean host = lobby != null && lobby.getLeaderID() == userId;
        boolean required = MatchRepository.hasPendingReviewForUser(lobbyId, userId);
        if (!required) return List.of();
        List<ActionRow> rows = new ArrayList<>();
        rows.add(ActionRow.of(Button.success("matchBatchApprove-" + lobbyId,
                t(userId, "Match.Review.ApproveAll"))));
        if (!host) {
            List<MatchRepository.MatchInfo> editable = MatchRepository.getForLobby(lobbyId).stream()
                    .filter(match -> "PENDING_CONFIRMATION".equals(match.status()))
                    .filter(match -> MatchRepository.isParticipant(match.id(), userId)).limit(25).toList();
            if (!editable.isEmpty()) {
                rows.add(ActionRow.of(StringSelectMenu.create("matchBatchEdit-" + lobbyId)
                        .setPlaceholder(t(userId, "Match.Review.EditSelect"))
                        .addOptions(editable.stream().map(match -> SelectOption.of(
                                t(userId, "Match.Review.Match", Map.of("%match%", String.valueOf(match.sequenceNumber()))),
                                String.valueOf(match.id()))).toList()).build()));
            }
        }
        return rows;
    }

    private void sendLobbyReview(int lobbyId) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null) return;
        List<Integer> recipients = MatchRepository.reviewParticipants(lobbyId).stream()
                .filter(userId -> userId != lobby.getLeaderID()).toList();
        if (recipients.isEmpty()) {
            if (MatchRepository.completeLobbyReview(lobbyId)) updateReviewMessages(lobbyId);
            return;
        }
        recipients.forEach(userId -> sendReviewMessage(lobbyId, userId));
    }

    private void sendReviewMessage(int lobbyId, int userId) {
        UserObject user = UserController.get(userId);
        if (user == null) return;
        jda.retrieveUserById(user.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
                dm.sendMessageEmbeds(reviewEmbeds(lobbyId, userId)).setComponents(reviewComponents(lobbyId, userId))
                        .queue(message -> MatchRepository.storeReviewMessage(lobbyId, userId, message.getId()))));
    }

    private void ensureHostReviewMessage(int lobbyId) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null) return;
        boolean exists = MatchRepository.getReviewMessages(lobbyId).stream()
                .anyMatch(message -> message.userId() == lobby.getLeaderID());
        if (!exists) sendReviewMessage(lobbyId, lobby.getLeaderID());
    }

    private void updateReviewMessages(int lobbyId) {
        MatchRepository.MatchReview review = MatchRepository.getReview(lobbyId);
        sendProfileNotifications(lobbyId);
        if (review != null && "COMPLETE".equals(review.status())) {
            deleteReviewMessages(lobbyId);
            return;
        }
        if (review != null && review.requiresHost() && "REVIEW".equals(review.status())) ensureHostReviewMessage(lobbyId);
        for (MatchRepository.ReviewMessage stored : MatchRepository.getReviewMessages(lobbyId)) {
            UserObject user = UserController.get(stored.userId());
            if (user == null) continue;
            jda.retrieveUserById(user.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
                    dm.retrieveMessageById(stored.messageId()).queue(message -> message
                            .editMessageEmbeds(reviewEmbeds(lobbyId, stored.userId()))
                            .setComponents(reviewComponents(lobbyId, stored.userId())).queue(), ignored -> {})));
        }
    }

    private void sendProfileNotifications(int lobbyId) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        for (MatchRepository.ProfileNotification notification : MatchRepository.claimProfileNotifications(lobbyId)) {
            UserObject user = UserController.get(notification.userId());
            if (user == null) continue;
            UserObject sender = lobby == null ? null : UserController.get(lobby.getLeaderID());
            if (sender == null) sender = user;
            String matches = notification.matchSequences().stream().map(sequence -> "#" + sequence)
                    .reduce((first, next) -> first + ", " + next).orElse("-");
            InboxService.sendToUser(sender, user, t(user.getId(), "Match.ProfileSaved.Title"),
                    t(user.getId(), "Match.ProfileSaved.Description", Map.of(
                            "%matches%", matches, "%lobby%", String.valueOf(lobbyId))),
                    InboxMessageRepository.DeliveryMode.SILENT);
        }
    }

    private void deleteReviewMessages(int lobbyId) {
        List<MatchRepository.ReviewMessage> messages = MatchRepository.getReviewMessages(lobbyId);
        MatchRepository.deleteReviewMessages(lobbyId);
        for (MatchRepository.ReviewMessage stored : messages) {
            UserObject user = UserController.get(stored.userId());
            if (user == null) continue;
            jda.retrieveUserById(user.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
                    dm.deleteMessageById(stored.messageId()).queue(null, ignored -> {})));
        }
    }

    private void deleteHostEntryMessage(MatchRepository.MatchReview review) {
        if (review.hostMessageId() == null || review.hostMessageId().isBlank()) return;
        LobbyObject lobby = LobbyRepository.get(review.lobbyId());
        UserObject host = lobby == null ? null : UserController.get(lobby.getLeaderID());
        if (host == null) return;
        jda.retrieveUserById(host.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
                dm.deleteMessageById(review.hostMessageId()).queue(null, ignored -> {})));
    }

    private static String valueLabel(MatchRepository.MatchStatValue value) {
        String target = value.userId() != null ? value.username() : value.teamName();
        return value.name() + (target == null || target.isBlank() ? "" : " · " + target);
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static List<String> splitPages(String value, int maxLength, int maxPages) {
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        for (String line : value.split("\\n", -1)) {
            if (page.length() + line.length() + 1 > maxLength && !page.isEmpty()) {
                pages.add(page.toString()); page.setLength(0);
                if (pages.size() == maxPages) break;
            }
            if (line.length() > maxLength) line = limit(line, maxLength);
            if (!page.isEmpty()) page.append('\n');
            page.append(line);
        }
        if (!page.isEmpty() && pages.size() < maxPages) pages.add(page.toString());
        if (pages.isEmpty()) pages.add("-");
        return pages;
    }

    private static String t(int userId, String key) {
        return LanguageManager.getMessageForUser(key, userId);
    }

    private static String t(int userId, String key, Map<String, String> values) {
        return LanguageManager.getMessageForUser(key, userId, values);
    }

    private static boolean valid(GameStatDefinition.ValueType type, String value) {
        if (value.isBlank()) return false;
        try {
            return switch (type) {
                case INTEGER -> { Integer.parseInt(value); yield true; }
                case DECIMAL -> { Double.parseDouble(value); yield true; }
                case BOOLEAN -> value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
                case TEXT -> value.length() <= 1500;
            };
        } catch (NumberFormatException e) { return false; }
    }

    public record PlayerTarget(int userId, String username) {}
    public record StatTask(GameStatDefinition definition, Integer userId, Integer teamId, String label) {}
    public record CorrectionTask(MatchRepository.MatchStatValue value, String label) {}

    public static final class EntrySession {
        private final int matchId, lobbyId, hostId, teamId;
        private final List<PlayerTarget> candidates;
        private List<PlayerTarget> players = List.of();
        private List<StatTask> tasks = List.of();
        private boolean participantsSelected;
        private int cursor;
        EntrySession(int matchId, int lobbyId, int hostId, int teamId, List<PlayerTarget> candidates) {
            this.matchId = matchId; this.lobbyId = lobbyId; this.hostId = hostId; this.teamId = teamId;
            this.candidates = List.copyOf(candidates);
        }
        public int matchId() { return matchId; }
        public int lobbyId() { return lobbyId; }
        public int hostId() { return hostId; }
        public int teamId() { return teamId; }
        public List<PlayerTarget> candidates() { return candidates; }
        public List<PlayerTarget> players() { return players; }
        public List<StatTask> tasks() { return tasks; }
        public int cursor() { return cursor; }
        public boolean participantsSelected() { return participantsSelected; }
        void selectParticipants(List<PlayerTarget> selectedPlayers, List<StatTask> selectedTasks) {
            players = List.copyOf(selectedPlayers); tasks = List.copyOf(selectedTasks); participantsSelected = true;
        }
        void advance(int amount) { cursor += amount; }
        public boolean complete() { return participantsSelected && cursor >= tasks.size(); }
    }

    public static final class CorrectionSession {
        private final int matchId, userId;
        private final List<CorrectionTask> tasks;
        private final Map<Integer, String> proposedValues = new LinkedHashMap<>();
        private int cursor;
        CorrectionSession(int matchId, int userId, List<CorrectionTask> tasks) {
            this.matchId = matchId; this.userId = userId; this.tasks = List.copyOf(tasks);
        }
        public int matchId() { return matchId; }
        public int userId() { return userId; }
        public List<CorrectionTask> tasks() { return tasks; }
        public Map<Integer, String> proposedValues() { return proposedValues; }
        public int cursor() { return cursor; }
        void advance(int amount) { cursor += amount; }
        void rewind(int amount) { cursor = Math.max(0, cursor - amount); }
        public boolean complete() { return cursor >= tasks.size(); }
    }
}
