package de.flolang.matchyourgame.manager.lobby;

import de.flolang.matchyourgame.database.friend.FriendRepository;
import de.flolang.matchyourgame.database.game.GameOption;
import de.flolang.matchyourgame.database.game.RankCompatibilityRepository;
import de.flolang.matchyourgame.database.lobby.*;
import de.flolang.matchyourgame.database.party.PartyObject;
import de.flolang.matchyourgame.database.party.PartyRepository;
import de.flolang.matchyourgame.database.profile.GameProfile;
import de.flolang.matchyourgame.database.profile.GameProfileRepository;
import de.flolang.matchyourgame.database.profile.CommunicationLanguageRepository;
import de.flolang.matchyourgame.manager.queue.QueueMatcher;
import de.flolang.matchyourgame.manager.ManagementMessageUpdater;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.logging.DiscordLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public final class LobbyService {
    public static final Duration INVITATION_WAVE_INTERVAL = Duration.ofMinutes(5);
    public static final Duration VOICE_JOIN_TIMEOUT = Duration.ofMinutes(6);
    private static final Logger LOGGER = LoggerFactory.getLogger(LobbyService.class);

    private final LobbyDiscordCoordinator discord;
    private final ConcurrentHashMap<Integer, Object> joinLocks = new ConcurrentHashMap<>();

    public LobbyService(LobbyDiscordCoordinator discord) {
        this.discord = discord;
    }

    public LobbyObject create(int leaderId, int gameId, int capacity, String platform, String region,
                              String language, int rankMin, int rankMax, String role) {
        if (capacity < 2 || capacity > 99 || rankMin > rankMax) return null;
        if (LobbyRepository.getActiveForUser(leaderId) != null) return null;
        PartyObject party = PartyRepository.getForUser(leaderId);
        if (party != null && (party.hostUserId() != leaderId || party.memberIds().size() > capacity)) return null;
        LobbyObject lobby = LobbyRepository.create(gameId, leaderId, capacity, platform, region, language,
                rankMin, rankMax, role);
        if (lobby == null) return null;
        if (party == null || party.memberIds().size() == 1) {
            if (party != null) PartyRepository.touch(party.id());
            ManagementMessageUpdater.refreshFriendActivity(leaderId);
            return lobby;
        }
        if (!joiningProfilesMatch(lobby, party.memberIds())) {
            LobbyRepository.cancel(lobby.getId());
            return null;
        }
        List<Integer> others = party.memberIds().stream().filter(memberId -> memberId != leaderId).toList();
        try {
            if (!LobbyRepository.addMembersAtomically(lobby.getId(), others, party.id())) {
                LobbyRepository.cancel(lobby.getId());
                return null;
            }
        } catch (SQLException exception) {
            LOGGER.error("Could not move party {} into lobby {}", party.id(), lobby.getId(), exception);
            LobbyRepository.cancel(lobby.getId());
            return null;
        }
        PartyRepository.touch(party.id());
        ManagementMessageUpdater.refreshFriendActivity(party.memberIds());
        return LobbyRepository.get(lobby.getId());
    }

    public LobbyInvitation inviteFriend(int leaderId, int lobbyId, String username) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        UserObject friend = UserController.get(username);
        if (lobby == null || lobby.getLeaderID() != leaderId || !lobby.isOpen() || friend == null) return null;
        if (!FriendRepository.areFriends(leaderId, friend.getId())) return null;
        if (!joiningProfilesMatch(lobby, List.of(friend.getId()))) return null;
        return createAndSendInvitation(lobby, friend.getId(), InvitationSource.FRIEND);
    }

    public QueueStartResult activatePassiveQueue(int actorId, int lobbyId) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null || lobby.getLeaderID() != actorId || !lobby.isOpen()) return null;
        LobbyRepository.setPassiveQueue(lobbyId, true);
        lobby = LobbyRepository.get(lobbyId);
        int freeSlots = Math.max(0, lobby.getMaxPlayers() - LobbyRepository.memberCount(lobbyId));
        int sent = sendRanked(lobby, SearchProfileRepository.passiveCandidates(lobby), freeSlots,
                InvitationSource.PASSIVE_QUEUE);
        LobbyRepository.markInvitationWave(lobbyId);
        return new QueueStartResult(sent);
    }

    public boolean deactivatePassiveQueue(int actorId, int lobbyId) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null || lobby.getLeaderID() != actorId || !lobby.isOpen()) return false;
        LobbyRepository.setPassiveQueue(lobbyId, false);
        return true;
    }

    public LobbySettingsUpdate updateSettings(int actorId, int lobbyId, int capacity,
                                              Integer requestedRankMin, Integer requestedRankMax,
                                              boolean unrestrictedRanks) {
        synchronized (joinLocks.computeIfAbsent(lobbyId, ignored -> new Object())) {
            return updateSettingsLocked(actorId, lobbyId, capacity, requestedRankMin, requestedRankMax,
                    unrestrictedRanks);
        }
    }

    private LobbySettingsUpdate updateSettingsLocked(int actorId, int lobbyId, int capacity,
                                                     Integer requestedRankMin, Integer requestedRankMax,
                                                     boolean unrestrictedRanks) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null || lobby.getLeaderID() != actorId || !lobby.isOpen())
            return new LobbySettingsUpdate(LobbySettingsStatus.UNAVAILABLE, lobby, null, null);
        int members = LobbyRepository.memberCount(lobbyId);
        if (capacity < 2 || capacity > 99)
            return new LobbySettingsUpdate(LobbySettingsStatus.INVALID_CAPACITY, lobby, null, null);
        if (capacity < members)
            return new LobbySettingsUpdate(LobbySettingsStatus.CAPACITY_BELOW_MEMBERS, lobby, null, null);

        Integer unrestrictedSize = RankCompatibilityRepository.getUnrestrictedPartySize(lobby.getGameID());
        if (unrestrictedRanks && !LobbyCapacityRules.offersUnrestrictedRankChoice(capacity, unrestrictedSize))
            return new LobbySettingsUpdate(LobbySettingsStatus.UNRESTRICTED_NOT_AVAILABLE, lobby, null, null);

        Integer effectiveMin = null;
        Integer effectiveMax = null;
        if (!unrestrictedRanks) {
            List<GameOption> allowed = compatibleRanksForCurrentMembers(lobby);
            if (!allowed.isEmpty()) {
                int allowedMin = allowed.getFirst().sortOrder();
                int allowedMax = allowed.getLast().sortOrder();
                effectiveMin = requestedRankMin == null ? allowedMin : requestedRankMin;
                effectiveMax = requestedRankMax == null ? allowedMax : requestedRankMax;
                if (effectiveMin > effectiveMax)
                    return new LobbySettingsUpdate(LobbySettingsStatus.INVALID_RANK_RANGE, lobby, null, null);
                if (effectiveMin < allowedMin || effectiveMax > allowedMax
                        || !containsRankOrder(allowed, effectiveMin) || !containsRankOrder(allowed, effectiveMax))
                    return new LobbySettingsUpdate(LobbySettingsStatus.RANK_OUTSIDE_ALLOWED, lobby, allowedMin, allowedMax);

                List<Integer> memberRanks = currentMemberRanks(lobby);
                if (!memberRanks.isEmpty()) {
                    effectiveMin = Math.min(effectiveMin, java.util.Collections.min(memberRanks));
                    effectiveMax = Math.max(effectiveMax, java.util.Collections.max(memberRanks));
                }
                if (effectiveMin < allowedMin || effectiveMax > allowedMax)
                    return new LobbySettingsUpdate(LobbySettingsStatus.RANK_OUTSIDE_ALLOWED, lobby, allowedMin, allowedMax);
                if (effectiveMin == allowedMin && effectiveMax == allowedMax) {
                    effectiveMin = null;
                    effectiveMax = null;
                }
            } else if (requestedRankMin != null || requestedRankMax != null) {
                return new LobbySettingsUpdate(LobbySettingsStatus.RANKS_NOT_AVAILABLE, lobby, null, null);
            }
        }
        if (!LobbyRepository.updateSettings(lobbyId, capacity, effectiveMin, effectiveMax, unrestrictedRanks))
            return new LobbySettingsUpdate(LobbySettingsStatus.UNAVAILABLE, LobbyRepository.get(lobbyId), null, null);
        LobbyObject updated = LobbyRepository.get(lobbyId);
        discord.refreshManagementMessages(updated);
        prepareVoiceIfFull(lobbyId);
        return new LobbySettingsUpdate(LobbySettingsStatus.UPDATED, updated, effectiveMin, effectiveMax);
    }

    public List<GameOption> compatibleRanksForCurrentMembers(LobbyObject lobby) {
        return RankCompatibilityRepository.getCompatibleRanksForSources(lobby.getGameID(), currentMemberRanks(lobby));
    }

    public int inviteClan(int actorId, int lobbyId, int clanId) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null || lobby.getLeaderID() != actorId || !lobby.isOpen()
                || !ClanRepository.canInvite(clanId, actorId)
                || !ClanRepository.supportsGame(clanId, lobby.getGameID())) return 0;
        if (!LobbyRepository.addClanQueue(lobbyId, clanId)) return 0;
        lobby = LobbyRepository.get(lobbyId);
        int sent = sendRanked(lobby, ClanRepository.queuedCandidates(lobby), 20, InvitationSource.CLAN);
        if (!hasEligibleCandidates(lobby, ClanRepository.queuedCandidates(lobby), InvitationSource.CLAN))
            LobbyRepository.setClanQueue(lobbyId, false);
        LobbyRepository.markInvitationWave(lobbyId);
        return sent;
    }

    public LobbyJoinResult acceptInvitation(int invitationId, int userId) {
        LobbyInvitation invitation = LobbyInvitationRepository.get(invitationId);
        if (invitation == null || invitation.userId() != userId)
            return LobbyJoinResult.LOBBY_NOT_FOUND;
        clearPassiveQueueCooldown(invitation);
        if (invitation.status() != LobbyInvitation.Status.PENDING) {
            clearCooldownIfUnavailable(invitation.lobbyId(), userId);
            return LobbyJoinResult.LOBBY_NOT_FOUND;
        }
        PartyObject party = PartyRepository.getForUser(userId);
        if (invitation.source() == InvitationSource.PASSIVE_QUEUE && party != null)
            return LobbyJoinResult.PARTY_HOST_REQUIRED;
        boolean leaveParty = party != null && invitation.source() != InvitationSource.PASSIVE_QUEUE;
        LobbyJoinResult result = joinAndHandleUnavailable(invitation.lobbyId(), userId, !leaveParty);
        if (result == LobbyJoinResult.JOINED)
            LobbyInvitationRepository.respond(invitationId, LobbyInvitation.Status.ACCEPTED);
        if (result == LobbyJoinResult.JOINED && leaveParty) {
            List<Integer> formerPartyMembers = party.memberIds();
            if (PartyRepository.leaveAndTransferHost(party.id(), userId))
                ManagementMessageUpdater.refreshPartyState(formerPartyMembers);
        }
        return result;
    }

    public boolean declineInvitation(int invitationId, int userId) {
        LobbyInvitation invitation = LobbyInvitationRepository.get(invitationId);
        if (invitation == null || invitation.userId() != userId) return false;
        clearPassiveQueueCooldown(invitation);
        return invitation.status() == LobbyInvitation.Status.PENDING
                && LobbyInvitationRepository.respond(invitationId, LobbyInvitation.Status.DECLINED);
    }

    public LobbyJoinResult joinBrowse(int lobbyId, int userId) {
        return joinAndHandleUnavailable(lobbyId, userId, true);
    }

    public LobbyJoinResult addLanguageAndJoin(int lobbyId, int userId, String language) {
        boolean offered = LobbyLanguageRepository.get(lobbyId).stream().anyMatch(language::equalsIgnoreCase);
        if (!offered || !CommunicationLanguageRepository.addAtEnd(userId, language))
            return LobbyJoinResult.LANGUAGE_MISMATCH;
        return joinAndHandleUnavailable(lobbyId, userId, true);
    }

    public List<LobbyObject> browse(SearchProfile profile) {
        if (profile == null) return List.of();
        return LobbyRepository.getOpenForGame(profile.gameId()).stream()
                .filter(lobby -> QueueMatcher.matches(lobby, profile))
                .filter(lobby -> rankMatchesCurrentMembers(lobby, profile.rankValue()))
                .filter(lobby -> languagesMatch(lobby, List.of(profile.userId())))
                .filter(lobby -> !LobbyRepository.memberIds(lobby.getId()).contains(profile.userId())).toList();
    }

    public boolean isPassiveQueueAvailable(int userId) {
        return discord.isPassiveQueueAvailable(userId);
    }

    public void prepareVoiceIfFull(int lobbyId) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby != null && lobby.isOpen()
                && LobbyRepository.memberCount(lobbyId) >= lobby.getMaxPlayers())
            discord.prepareVoiceChannel(lobby);
    }

    public void refreshManagementMessages(int lobbyId) {
        discord.refreshManagementMessages(LobbyRepository.get(lobbyId));
    }

    public boolean close(int lobbyId, int hostId) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null || lobby.getLeaderID() != hostId || lobby.getStatus() == LobbyStatus.CLOSED) return false;
        discord.deleteVoiceChannel(lobby);
        LobbyRepository.close(lobbyId);
        discord.refreshMainManagementMessages(lobby);
        ManagementMessageUpdater.refreshFriendActivity(LobbyRepository.memberIds(lobbyId));
        discord.promptGameProfileUpdates(lobby);
        return true;
    }

    public boolean startWithCurrentPlayers(int lobbyId, int hostId) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        int players = LobbyRepository.memberCount(lobbyId);
        if (lobby == null || lobby.getLeaderID() != hostId || !lobby.isOpen() || players < 2) return false;
        LobbyRepository.configureQueues(lobbyId, false, false);
        LobbyRepository.setCapacity(lobbyId, players);
        discord.prepareVoiceChannel(LobbyRepository.get(lobbyId));
        return true;
    }

    public boolean dissolve(int lobbyId, int hostId) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null || lobby.getLeaderID() != hostId || !lobby.isOpen()) return false;
        LobbyRepository.cancel(lobbyId);
        ManagementMessageUpdater.refreshFriendActivity(LobbyRepository.memberIds(lobbyId));
        return true;
    }

    public List<LobbyObject> findMergeCandidates(int sourceLobbyId, int sourceHostId) {
        LobbyObject source = LobbyRepository.get(sourceLobbyId);
        if (source == null || source.getLeaderID() != sourceHostId || !source.isOpen()) return List.of();
        List<Integer> sourceMembers = LobbyRepository.memberIds(sourceLobbyId);
        return LobbyRepository.getOpenForGame(source.getGameID()).stream()
                .filter(target -> target.getId() != sourceLobbyId && target.isPassiveQueue())
                .filter(target -> sourceMembers.size() + LobbyRepository.memberCount(target.getId())
                        <= target.getMaxPlayers())
                .filter(target -> mergeSettingsMatch(source, target, sourceMembers,
                        LobbyRepository.memberIds(target.getId())))
                .sorted(java.util.Comparator
                        .comparingInt((LobbyObject target) -> target.getMaxPlayers()
                                - LobbyRepository.memberCount(target.getId()) - sourceMembers.size())
                        .thenComparing(LobbyObject::getCreatedAt))
                .toList();
    }

    public LobbyMergeResult mergeLobbies(int sourceLobbyId, int targetLobbyId, int sourceHostId) {
        int first = Math.min(sourceLobbyId, targetLobbyId);
        int second = Math.max(sourceLobbyId, targetLobbyId);
        synchronized (joinLocks.computeIfAbsent(first, ignored -> new Object())) {
            synchronized (joinLocks.computeIfAbsent(second, ignored -> new Object())) {
                LobbyObject source = LobbyRepository.get(sourceLobbyId);
                LobbyObject target = LobbyRepository.get(targetLobbyId);
                if (source == null || target == null || source.getLeaderID() != sourceHostId
                        || !source.isOpen() || !target.isOpen() || !target.isPassiveQueue())
                    return LobbyMergeResult.UNAVAILABLE;
                List<Integer> sourceMembers = LobbyRepository.memberIds(sourceLobbyId);
                List<Integer> targetMembers = LobbyRepository.memberIds(targetLobbyId);
                if (sourceMembers.size() + targetMembers.size() > target.getMaxPlayers())
                    return LobbyMergeResult.NOT_ENOUGH_SPACE;
                if (!mergeSettingsMatch(source, target, sourceMembers, targetMembers))
                    return LobbyMergeResult.INCOMPATIBLE;
                List<String> commonLanguages = commonMergeLanguages(source, target, sourceMembers, targetMembers);
                if (commonLanguages == null) return LobbyMergeResult.INCOMPATIBLE;
                if (!LobbyRepository.mergeOpenLobbies(sourceLobbyId, targetLobbyId, sourceHostId))
                    return LobbyMergeResult.UNAVAILABLE;
                LobbyLanguageRepository.set(targetLobbyId, commonLanguages);
                LobbyObject merged = LobbyRepository.get(targetLobbyId);
                ManagementMessageUpdater.refreshFriendActivity(sourceMembers);
                discord.refreshManagementMessages(merged);
                prepareVoiceIfFull(targetLobbyId);
                DiscordLogService.action("LOBBY_MERGE", "Lobby #" + sourceLobbyId + " mit "
                        + sourceMembers.size() + " Spielern wurde in Lobby #" + targetLobbyId + " zusammengeführt");
                return LobbyMergeResult.MERGED;
            }
        }
    }

    private boolean mergeSettingsMatch(LobbyObject source, LobbyObject target,
                                       List<Integer> sourceMembers, List<Integer> targetMembers) {
        if (source.getGameID() != target.getGameID()) return false;
        return joiningProfilesMatch(target, sourceMembers)
                && joiningProfilesMatch(source, targetMembers)
                && commonMergeLanguages(source, target, sourceMembers, targetMembers) != null;
    }

    private List<String> commonMergeLanguages(LobbyObject source, LobbyObject target,
                                              List<Integer> sourceMembers, List<Integer> targetMembers) {
        List<String> sourceLanguages = LobbyLanguageRepository.get(source.getId());
        List<String> targetLanguages = LobbyLanguageRepository.get(target.getId());
        if (sourceLanguages.isEmpty() && targetLanguages.isEmpty()) return List.of();
        List<String> common = new ArrayList<>();
        if (sourceLanguages.isEmpty()) common.addAll(targetLanguages);
        else if (targetLanguages.isEmpty()) common.addAll(sourceLanguages);
        else for (String language : sourceLanguages)
            if (targetLanguages.stream().anyMatch(language::equalsIgnoreCase)) common.add(language);
        if (common.isEmpty()) return null;
        List<Integer> allMembers = new ArrayList<>(sourceMembers);
        allMembers.addAll(targetMembers);
        if (!common.isEmpty()) common = LobbyLanguageRepository.intersectionForUsers(common, allMembers);
        return common.isEmpty() ? null : common;
    }

    public enum LobbyMergeResult { MERGED, UNAVAILABLE, NOT_ENOUGH_SPACE, INCOMPATIBLE }

    public void processInvitationWaves() {
        processVoiceTimeouts();
        processVoiceReminders();
        processVoiceRejoinTimeouts();
        for (LobbyObject lobby : LobbyRepository.getDueForInvitationWave(INVITATION_WAVE_INTERVAL)) {
            int freeSlots = lobby.getMaxPlayers() - LobbyRepository.memberCount(lobby.getId());
            if (freeSlots <= 0) {
                discord.prepareVoiceChannel(lobby);
                continue;
            }
            if (lobby.isClanQueue()) {
                sendRanked(lobby, ClanRepository.queuedCandidates(lobby), 20, InvitationSource.CLAN);
                if (!hasEligibleCandidates(lobby, ClanRepository.queuedCandidates(lobby), InvitationSource.CLAN))
                    LobbyRepository.setClanQueue(lobby.getId(), false);
            }
            if (lobby.isPassiveQueue()) {
                int sent = sendRanked(lobby, SearchProfileRepository.passiveCandidates(lobby), freeSlots,
                        InvitationSource.PASSIVE_QUEUE);
                if (sent == 0) {
                    LobbyRepository.setPassiveQueue(lobby.getId(), false);
                    discord.notifyPassiveQueueExhausted(LobbyRepository.get(lobby.getId()));
                }
            }
            LobbyRepository.markInvitationWave(lobby.getId());
        }
    }

    public void markVoiceJoined(long voiceChannelId, long discordUserId) {
        LobbyObject lobby = LobbyRepository.getByVoiceChannel(voiceChannelId);
        UserObject user = UserController.get(discordUserId);
        if (lobby != null && user != null && LobbyRepository.memberIds(lobby.getId()).contains(user.getId())) {
            LobbyRepository.markVoiceJoined(lobby.getId(), user.getId());
            if (LobbyRepository.missingVoiceMembers(lobby.getId()).isEmpty()) {
                LobbyRepository.markActive(lobby.getId());
                discord.refreshManagementMessages(LobbyRepository.get(lobby.getId()));
            }
        }
    }

    public void markVoiceLeft(long voiceChannelId, long discordUserId) {
        LobbyObject lobby = LobbyRepository.getByVoiceChannel(voiceChannelId);
        UserObject user = UserController.get(discordUserId);
        if (lobby == null || user == null || !List.of(LobbyStatus.FORMING, LobbyStatus.READY, LobbyStatus.ACTIVE)
                .contains(lobby.getStatus())
                || !LobbyRepository.memberIds(lobby.getId()).contains(user.getId())) return;
        LobbyRepository.markVoiceLeft(lobby.getId(), user.getId());
        discord.notifyVoiceLeft(user.getId(), lobby.getId());
    }

    public int closeEmptyVoiceChannel(long voiceChannelId) {
        LobbyObject lobby = LobbyRepository.getByVoiceChannel(voiceChannelId);
        if (lobby == null || (lobby.getStatus() != LobbyStatus.READY && lobby.getStatus() != LobbyStatus.ACTIVE)) return 0;
        discord.deleteVoiceChannel(lobby);
        LobbyRepository.close(lobby.getId());
        discord.refreshMainManagementMessages(lobby);
        ManagementMessageUpdater.refreshFriendActivity(LobbyRepository.memberIds(lobby.getId()));
        discord.promptGameProfileUpdates(lobby);
        discord.notifyAutomaticClosure(lobby);
        return lobby.getId();
    }

    public boolean extendVoiceDeadline(int lobbyId, int userId) {
        return LobbyRepository.extendVoiceDeadline(lobbyId, userId);
    }

    public boolean kickMember(int lobbyId, int actorId, int targetId, String reason) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null || lobby.getLeaderID() != actorId || actorId == targetId
                || !LobbyRepository.memberIds(lobbyId).contains(targetId)) return false;
        discord.removeVoiceAccess(lobby, targetId);
        removeAndReopen(lobby, List.of(targetId));
        discord.notifyLobbyKick(targetId, lobbyId, reason == null || reason.isBlank()
                ? t(targetId, "Lobby.Kick.NoReason") : reason.trim());
        return true;
    }

    public boolean leaveLobby(int lobbyId, int userId) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null || !List.of(LobbyStatus.OPEN, LobbyStatus.FORMING, LobbyStatus.READY, LobbyStatus.ACTIVE)
                .contains(lobby.getStatus()) || !LobbyRepository.memberIds(lobbyId).contains(userId)) return false;
        discord.removeVoiceAccess(lobby, userId);
        removeAndReopen(lobby, List.of(userId));
        return true;
    }

    public void excludeBannedUser(int userId) {
        LobbyObject lobby = LobbyRepository.getActiveForUser(userId);
        if (lobby != null && LobbyRepository.memberIds(lobby.getId()).contains(userId))
            removeAndReopen(lobby, List.of(userId));
    }

    private void processVoiceTimeouts() {
        for (LobbyObject lobby : LobbyRepository.getDueForVoiceCheck(VOICE_JOIN_TIMEOUT)) {
            List<Integer> affected = LobbyRepository.memberIds(lobby.getId());
            List<Integer> allMissing = LobbyRepository.missingVoiceMembers(lobby.getId());
            if (allMissing.isEmpty()) { LobbyRepository.markActive(lobby.getId()); continue; }
            List<Integer> missing = LobbyRepository.missingInitialVoiceMembers(lobby.getId());
            if (missing.isEmpty()) continue;
            for (int userId : missing) discord.notifyLobbyKick(userId, lobby.getId(),
                    t(userId, "Lobby.Kick.VoiceJoinTimeout"));
            discord.deleteVoiceChannel(lobby);
            LobbyRepository.excludeUsers(lobby.getId(), missing);
            LobbyRepository.removeMembers(lobby.getId(), missing);
            if (missing.contains(lobby.getLeaderID())) {
                Integer nextHost = LobbyRepository.earliestActiveMember(lobby.getId());
                if (nextHost == null) {
                    LobbyRepository.close(lobby.getId());
                    ManagementMessageUpdater.refreshFriendActivity(affected);
                    for (int userId : missing) discord.refreshMainManagementMessage(userId);
                    continue;
                }
                LobbyRepository.promoteHost(lobby.getId(), nextHost);
                discord.notifyHostPromotion(nextHost, lobby.getId());
            }
            LobbyRepository.reopenAfterVoiceTimeout(lobby.getId());
            ManagementMessageUpdater.refreshFriendActivity(affected);
            LobbyObject updated = LobbyRepository.get(lobby.getId());
            if (updated != null) discord.refreshManagementMessages(updated);
            for (int userId : missing) discord.refreshMainManagementMessage(userId);
        }
    }

    private void processVoiceReminders() {
        for (LobbyObject lobby : LobbyRepository.getDueForVoiceReminder()) {
            List<Integer> missing = LobbyRepository.missingInitialVoiceMembers(lobby.getId());
            for (int userId : missing) discord.notifyVoiceReminder(userId, lobby.getId());
            LobbyRepository.markVoiceReminderSent(lobby.getId());
        }
    }

    private void processVoiceRejoinTimeouts() {
        for (LobbyObject lobby : LobbyRepository.getActiveWithVoice()) {
            List<Integer> timedOut = LobbyRepository.dueVoiceRejoinTimeouts(lobby.getId());
            if (!timedOut.isEmpty()) {
                for (int userId : timedOut) discord.notifyLobbyKick(userId, lobby.getId(),
                        t(userId, "Lobby.Kick.VoiceRejoinTimeout"));
                removeAndReopen(lobby, timedOut);
            }
        }
    }

    private void removeAndReopen(LobbyObject lobby, List<Integer> removed) {
        List<Integer> affected = LobbyRepository.memberIds(lobby.getId());
        discord.deleteVoiceChannel(lobby);
        LobbyRepository.excludeUsers(lobby.getId(), removed);
        LobbyRepository.removeMembers(lobby.getId(), removed);
        if (removed.contains(lobby.getLeaderID())) {
            Integer nextHost = LobbyRepository.earliestActiveMember(lobby.getId());
            if (nextHost == null) {
                LobbyRepository.close(lobby.getId());
                ManagementMessageUpdater.refreshFriendActivity(affected);
                for (int userId : removed) discord.refreshMainManagementMessage(userId);
                return;
            }
            LobbyRepository.promoteHost(lobby.getId(), nextHost);
            discord.notifyHostPromotion(nextHost, lobby.getId());
        }
        LobbyRepository.reopenAfterVoiceTimeout(lobby.getId());
        ManagementMessageUpdater.refreshFriendActivity(affected);
        LobbyObject updated = LobbyRepository.get(lobby.getId());
        if (updated != null) discord.refreshManagementMessages(updated);
        for (int userId : removed) discord.refreshMainManagementMessage(userId);
    }

    private LobbyJoinResult join(int lobbyId, int userId) {
        return join(lobbyId, userId, true);
    }

    private LobbyJoinResult join(int lobbyId, int userId, boolean includeParty) {
        synchronized (joinLocks.computeIfAbsent(lobbyId, ignored -> new Object())) {
            return joinLocked(lobbyId, userId, includeParty);
        }
    }

    private LobbyJoinResult joinLocked(int lobbyId, int userId, boolean includeParty) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby == null) return LobbyJoinResult.LOBBY_NOT_FOUND;
        if (!lobby.isOpen()) return LobbyJoinResult.LOBBY_CLOSED;
        if (LobbyRepository.memberIds(lobbyId).contains(userId)) return LobbyJoinResult.ALREADY_MEMBER;
        PartyObject party = includeParty ? PartyRepository.getForUser(userId) : null;
        List<Integer> joining = List.of(userId);
        Integer partyId = null;
        if (party != null) {
            if (party.hostUserId() != userId) return LobbyJoinResult.PARTY_HOST_REQUIRED;
            joining = party.memberIds(); partyId = party.id();
        }
        if (!languagesMatch(lobby, joining)) return LobbyJoinResult.LANGUAGE_MISMATCH;
        if (!joiningProfilesMatch(lobby, joining)) return LobbyJoinResult.PROFILE_MISMATCH;
        try {
            if (!LobbyRepository.addMembersAtomically(lobbyId, joining, partyId)) return LobbyJoinResult.PARTY_TOO_LARGE;
        } catch (SQLException e) {
            LOGGER.error("Could not join lobby {}", lobbyId, e); return LobbyJoinResult.DATABASE_ERROR;
        }
        if (partyId != null) PartyRepository.touch(partyId);
        LobbyLanguageRepository.set(lobbyId,
                LobbyLanguageRepository.intersectionForUsers(LobbyLanguageRepository.get(lobbyId), joining));
        ManagementMessageUpdater.refreshFriendActivity(joining);
        discord.refreshManagementMessages(LobbyRepository.get(lobbyId));
        if (LobbyRepository.memberCount(lobbyId) >= lobby.getMaxPlayers()) discord.prepareVoiceChannel(LobbyRepository.get(lobbyId));
        return LobbyJoinResult.JOINED;
    }

    private LobbyInvitation createAndSendInvitation(LobbyObject lobby, int userId, InvitationSource source) {
        LobbyInvitation invitation = LobbyInvitationRepository.create(lobby.getId(), userId, source);
        if (invitation != null) discord.sendInvitation(invitation, lobby);
        return invitation;
    }

    private int sendRanked(LobbyObject lobby, List<QueueCandidate> candidates, int limit, InvitationSource source) {
        int sent = 0;
        List<String> lobbyLanguages = LobbyLanguageRepository.get(lobby.getId());
        List<QueueCandidate> ranked = QueueMatcher.rank(lobby, candidates, Instant.now()).stream()
                .filter(candidate -> languagesMatch(lobby, List.of(candidate.profile().userId())))
                .sorted(java.util.Comparator.comparingInt(candidate ->
                        LobbyLanguageRepository.bestPriority(candidate.profile().userId(), lobbyLanguages))).toList();
        for (QueueCandidate candidate : ranked) {
            if (sent >= limit) break;
            int userId = candidate.profile().userId();
            if (!rankMatchesCurrentMembers(lobby, candidate.profile().rankValue())) continue;
            if (source == InvitationSource.PASSIVE_QUEUE
                    && (!discord.isPassiveQueueAvailable(userId) || PartyRepository.getForUser(userId) != null)) continue;
            if (createAndSendInvitation(lobby, userId, source) != null) {
                sent++;
                if (source == InvitationSource.PASSIVE_QUEUE)
                    SearchProfileRepository.markInvited(userId, lobby.getGameID());
            }
        }
        return sent;
    }

    private boolean hasEligibleCandidates(LobbyObject lobby, List<QueueCandidate> candidates, InvitationSource source) {
        return QueueMatcher.rank(lobby, candidates, Instant.now()).stream().anyMatch(candidate -> {
            int userId = candidate.profile().userId();
            return languagesMatch(lobby, List.of(userId))
                    && rankMatchesCurrentMembers(lobby, candidate.profile().rankValue())
                    && (source != InvitationSource.PASSIVE_QUEUE
                    || discord.isPassiveQueueAvailable(userId) && PartyRepository.getForUser(userId) == null);
        });
    }

    private boolean languagesMatch(LobbyObject lobby, List<Integer> userIds) {
        List<String> current = LobbyLanguageRepository.get(lobby.getId());
        return current.isEmpty() || !LobbyLanguageRepository.intersectionForUsers(current, userIds).isEmpty();
    }

    private LobbyJoinResult joinAndHandleUnavailable(int lobbyId, int userId, boolean includeParty) {
        LobbyObject before = LobbyRepository.get(lobbyId);
        LobbyJoinResult result = join(lobbyId, userId, includeParty);
        if (result == LobbyJoinResult.JOINED || before == null) return result;
        LobbyObject after = LobbyRepository.get(lobbyId);
        boolean unavailable = after == null || !after.isOpen()
                || LobbyRepository.memberCount(lobbyId) >= after.getMaxPlayers();
        if (unavailable) SearchProfileRepository.clearInvitationCooldown(userId, before.getGameID());
        return result;
    }

    private void clearCooldownIfUnavailable(int lobbyId, int userId) {
        LobbyObject lobby = LobbyRepository.get(lobbyId);
        if (lobby != null && (!lobby.isOpen()
                || LobbyRepository.memberCount(lobbyId) >= lobby.getMaxPlayers()))
            SearchProfileRepository.clearInvitationCooldown(userId, lobby.getGameID());
    }

    private void clearPassiveQueueCooldown(LobbyInvitation invitation) {
        if (invitation.source() != InvitationSource.PASSIVE_QUEUE) return;
        LobbyObject lobby = LobbyRepository.get(invitation.lobbyId());
        if (lobby != null)
            SearchProfileRepository.clearInvitationCooldown(invitation.userId(), lobby.getGameID());
    }

    public record QueueStartResult(int invitationsSent) {}

    public record LobbySettingsUpdate(LobbySettingsStatus status, LobbyObject lobby,
                                      Integer effectiveRankMin, Integer effectiveRankMax) {}

    public enum LobbySettingsStatus {
        UPDATED,
        UNAVAILABLE,
        INVALID_CAPACITY,
        CAPACITY_BELOW_MEMBERS,
        INVALID_RANK_RANGE,
        RANK_OUTSIDE_ALLOWED,
        RANKS_NOT_AVAILABLE,
        UNRESTRICTED_NOT_AVAILABLE
    }

    private boolean joiningProfilesMatch(LobbyObject lobby, List<Integer> joiningUserIds) {
        List<GameProfile> joiningProfiles = new ArrayList<>();
        for (int userId : joiningUserIds) {
            GameProfile profile = GameProfileRepository.getForGame(userId, lobby.getGameID()).stream()
                    .filter(candidate -> profileFiltersMatch(lobby, candidate))
                    .filter(candidate -> rankMatchesCurrentMembers(lobby, candidate.rankValue()))
                    .filter(candidate -> joiningProfiles.stream().allMatch(existing ->
                            lobby.isRankRulesUnrestricted() || RankCompatibilityRepository.isCompatible(
                                    lobby.getGameID(), existing.rankValue(), candidate.rankValue())))
                    .findFirst().orElse(null);
            if (profile == null) return false;
            joiningProfiles.add(profile);
        }
        return true;
    }

    private boolean rankMatchesCurrentMembers(LobbyObject lobby, int candidateRank) {
        if (lobby.isRankRulesUnrestricted()) return true;
        Integer customMin = lobby.getCustomRankMin();
        Integer customMax = lobby.getCustomRankMax();
        if ((customMin != null && candidateRank < customMin) || (customMax != null && candidateRank > customMax))
            return false;
        return compatibleRanksForCurrentMembers(lobby).stream()
                .anyMatch(rank -> rank.sortOrder() == candidateRank);
    }

    private List<Integer> currentMemberRanks(LobbyObject lobby) {
        List<Integer> memberRanks = new ArrayList<>();
        for (int memberId : LobbyRepository.memberIds(lobby.getId())) {
            GameProfile memberProfile = GameProfileRepository.getForGame(memberId, lobby.getGameID()).stream()
                    .filter(profile -> platformMatches(lobby.getPlatform(), profile.platform()))
                    .findFirst().orElse(null);
            if (memberProfile != null) memberRanks.add(memberProfile.rankValue());
        }
        return memberRanks;
    }

    private static boolean containsRankOrder(List<GameOption> ranks, int order) {
        for (GameOption rank : ranks) if (rank.sortOrder() == order) return true;
        return false;
    }

    private static boolean profileFiltersMatch(LobbyObject lobby, GameProfile profile) {
        return platformMatches(lobby.getPlatform(), profile.platform())
                && valueMatches(lobby.getRegion(), profile.region())
                && valueMatches(lobby.getPreferredRole(), profile.preferredRole());
    }

    private static boolean platformMatches(String lobbyPlatform, String profilePlatform) {
        return valueMatches(lobbyPlatform, profilePlatform);
    }

    private static boolean valueMatches(String left, String right) {
        return left == null || right == null || left.isBlank() || right.isBlank()
                || left.equalsIgnoreCase("ANY") || right.equalsIgnoreCase("ANY")
                || left.equalsIgnoreCase(right);
    }

    private static String t(int userId, String key) {
        return LanguageManager.getMessageForUser(key, userId);
    }
}
