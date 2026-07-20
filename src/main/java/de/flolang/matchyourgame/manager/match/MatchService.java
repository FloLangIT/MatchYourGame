package de.flolang.matchyourgame.manager.match;

import de.flolang.matchyourgame.database.game.GameStatDefinition;
import de.flolang.matchyourgame.database.game.GameStatRepository;
import de.flolang.matchyourgame.database.lobby.*;
import de.flolang.matchyourgame.database.match.MatchRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MatchService {
    private final JDA jda;
    private final Map<Integer, EntrySession> sessions = new ConcurrentHashMap<>();

    public MatchService(JDA jda) { this.jda = jda; }

    public EntrySession start(int lobbyId, int hostId) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null || lobby.getLeaderID() != hostId || lobby.getStatus() != LobbyStatus.CLOSED) return null;
        List<Integer> memberIds = LobbyRepository.memberIds(lobbyId);
        if (memberIds.isEmpty()) return null;
        int matchId = MatchRepository.create(lobbyId);
        if (matchId == 0) return null;
        int teamId = MatchRepository.addTeam(matchId, "Lobby");
        if (teamId == 0) return null;
        List<PlayerTarget> players = new ArrayList<>();
        for (int memberId : memberIds) {
            UserObject member = UserController.get(memberId);
            if (member == null || !MatchRepository.addParticipant(matchId, memberId, teamId)) return null;
            players.add(new PlayerTarget(memberId, member.getUsername()));
        }
        List<StatTask> tasks = new ArrayList<>();
        for (GameStatDefinition definition : GameStatRepository.getEffectiveForGame(lobby.getGameID())) {
            if (definition.scope() == GameStatDefinition.Scope.PLAYER) {
                for (PlayerTarget player : players) tasks.add(new StatTask(definition, player.userId(), null,
                        definition.name() + " · " + player.username()));
            } else {
                tasks.add(new StatTask(definition, null, teamId, definition.name()));
            }
        }
        EntrySession session = new EntrySession(matchId, lobbyId, hostId, players, tasks);
        sessions.put(matchId, session);
        if (tasks.isEmpty()) finish(session);
        return session;
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

    public void confirm(int matchId, int userId, boolean accepted) {
        if (MatchRepository.isParticipant(matchId, userId)) MatchRepository.confirm(matchId, userId, accepted, null);
    }

    private void finish(EntrySession session) {
        MatchRepository.submitForConfirmation(session.matchId());
        MatchRepository.confirm(session.matchId(), session.hostId(), true, null);
        for (PlayerTarget player : session.players()) {
            if (player.userId() == session.hostId()) continue;
            UserObject user = UserController.get(player.userId());
            if (user == null) continue;
            jda.retrieveUserById(user.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
                    dm.sendMessageEmbeds(new EmbedCreator().setTitle(LanguageManager.getMessageForUser("Match.Confirmation.Title", user.getId()))
                                    .setDescription(LanguageManager.getMessageForUser("Match.Confirmation.Description", user.getId(),
                                            java.util.Map.of("%matchId%", String.valueOf(session.matchId())))).build())
                            .setComponents(ActionRow.of(Button.success("matchConfirm-" + session.matchId(),
                                            LanguageManager.getMessageForUser("Match.Confirmation.Confirm", user.getId())),
                                    Button.danger("matchReject-" + session.matchId(),
                                            LanguageManager.getMessageForUser("Match.Confirmation.Dispute", user.getId())))).queue()));
        }
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

    public static final class EntrySession {
        private final int matchId, lobbyId, hostId;
        private final List<PlayerTarget> players;
        private final List<StatTask> tasks;
        private int cursor;
        EntrySession(int matchId, int lobbyId, int hostId, List<PlayerTarget> players, List<StatTask> tasks) {
            this.matchId = matchId; this.lobbyId = lobbyId; this.hostId = hostId;
            this.players = List.copyOf(players); this.tasks = List.copyOf(tasks);
        }
        public int matchId() { return matchId; }
        public int lobbyId() { return lobbyId; }
        public int hostId() { return hostId; }
        public List<PlayerTarget> players() { return players; }
        public List<StatTask> tasks() { return tasks; }
        public int cursor() { return cursor; }
        void advance(int amount) { cursor += amount; }
        public boolean complete() { return cursor >= tasks.size(); }
    }
}
