package de.flolang.matchyourgame.manager.lobby;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.guild.GuildObject;
import de.flolang.matchyourgame.database.guild.GuildRepository;
import de.flolang.matchyourgame.manager.PartnerGuildService;
import de.flolang.matchyourgame.database.game.GameObject;
import de.flolang.matchyourgame.database.game.GameOption;
import de.flolang.matchyourgame.database.game.GameOptionRepository;
import de.flolang.matchyourgame.database.game.GameRepository;
import de.flolang.matchyourgame.database.lobby.LobbyInvitation;
import de.flolang.matchyourgame.database.lobby.LobbyInvitationRepository;
import de.flolang.matchyourgame.database.lobby.LobbyLanguageRepository;
import de.flolang.matchyourgame.database.lobby.LobbyObject;
import de.flolang.matchyourgame.database.lobby.LobbyRepository;
import de.flolang.matchyourgame.database.lobby.PassiveQueueSettings;
import de.flolang.matchyourgame.database.lobby.PassiveQueueSettingsRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.profile.GameProfile;
import de.flolang.matchyourgame.database.profile.GameProfileRepository;
import de.flolang.matchyourgame.database.review.ReviewRepository;
import de.flolang.matchyourgame.database.party.PartyRepository;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.language.CommunicationLanguageNames;
import de.flolang.matchyourgame.manager.UserControlManager;
import de.flolang.matchyourgame.manager.review.RatingFormatter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.entities.Invite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class LobbyDiscordCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(LobbyDiscordCoordinator.class);
    private static final Set<Integer> PROVISIONING = ConcurrentHashMap.newKeySet();
    private static final java.util.Map<Integer, Set<Long>> ATTEMPTED_PARTNER_GUILDS = new ConcurrentHashMap<>();
    private static final EnumSet<Permission> LOBBY_VOICE_PERMISSIONS = EnumSet.of(
            Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK);
    private static final int PERMISSION_VERIFICATION_ATTEMPTS = 3;
    private final JDA jda;

    public LobbyDiscordCoordinator(JDA jda) { this.jda = jda; }

    public void sendInvitation(LobbyInvitation invitation, LobbyObject lobby) {
        UserObject recipient = UserController.get(invitation.userId());
        if (recipient == null || UserController.get(lobby.getLeaderID()) == null) return;
        jda.retrieveUserById(recipient.getDiscordID()).queue(user -> user.openPrivateChannel().queue(
                        channel -> channel.sendMessageEmbeds(invitationEmbed(invitation, lobby, recipient))
                                .setComponents(invitationControls(invitation, recipient))
                                .queue(message -> LobbyInvitationRepository.setDiscordMessageId(
                                                invitation.id(), message.getIdLong()),
                                        error -> LOGGER.warn("Could not send lobby invitation {}", invitation.id(), error)),
                        error -> LOGGER.warn("Could not open DM for user {}", recipient.getId())),
                error -> LOGGER.warn("Could not resolve Discord user {}", recipient.getDiscordID()));
    }

    public void refreshPendingInvitations(LobbyObject lobby) {
        if (lobby == null) return;
        for (LobbyInvitation invitation : LobbyInvitationRepository.getPendingForLobby(lobby.getId())) {
            if (invitation.discordMessageId() == 0) continue;
            UserObject recipient = UserController.get(invitation.userId());
            if (recipient == null) continue;
            jda.retrieveUserById(recipient.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
                    dm.retrieveMessageById(invitation.discordMessageId()).queue(message ->
                                    message.editMessageEmbeds(invitationEmbed(invitation, lobby, recipient))
                                            .setComponents(invitationControls(invitation, recipient)).queue(),
                            ignored -> { }), ignored -> { }), ignored -> { });
        }
    }

    public void prepareVoiceChannel(LobbyObject lobby) {
        if (lobby == null || !PROVISIONING.add(lobby.getId())) return;
        if (lobby.getVoiceChannelID() != 0) {
            prepareExistingVoiceChannel(lobby);
            return;
        }
        ATTEMPTED_PARTNER_GUILDS.put(lobby.getId(),ConcurrentHashMap.newKeySet());
        tryPartnerGuild(lobby,LobbyRepository.memberIds(lobby.getId()));
    }

    public void updateVoiceCapacity(LobbyObject lobby) {
        if (lobby == null || lobby.getVoiceChannelID() == 0) return;
        VoiceChannel channel = jda.getVoiceChannelById(lobby.getVoiceChannelID());
        if (channel != null)
            channel.getManager().setUserLimit(Math.min(lobby.getMaxPlayers(), VoiceChannel.MAX_USERLIMIT)).queue(null,
                    error -> LOGGER.warn("Could not update user limit for lobby voice {}", lobby.getId(), error));
    }

    private void prepareExistingVoiceChannel(LobbyObject lobby) {
        VoiceChannel channel = jda.getVoiceChannelById(lobby.getVoiceChannelID());
        if (channel == null) {
            PROVISIONING.remove(lobby.getId());
            LOGGER.warn("Stored voice channel for lobby {} is no longer available", lobby.getId());
            return;
        }
        LobbyRepository.beginExistingVoiceFormation(lobby.getId());
        List<Integer> missing = LobbyRepository.missingInitialVoiceMembers(lobby.getId());
        channel.getManager().setUserLimit(Math.min(lobby.getMaxPlayers(), VoiceChannel.MAX_USERLIMIT)).queue(
                ignored -> grantVoicePermissionsSequentially(lobby, channel, missing, 0, 1),
                error -> {
                    LOGGER.warn("Could not update user limit for existing lobby voice {}", lobby.getId(), error);
                    grantVoicePermissionsSequentially(lobby, channel, missing, 0, 1);
                });
    }

    private void tryPartnerGuild(LobbyObject lobby,List<Integer> userIds){
        if(lobby==null)return;
        Set<Long> attempted=ATTEMPTED_PARTNER_GUILDS.computeIfAbsent(lobby.getId(),ignored->ConcurrentHashMap.newKeySet());
        PartnerTarget target = selectPartnerGuild(lobby,attempted);
        if (target == null) {
            PROVISIONING.remove(lobby.getId());
            ATTEMPTED_PARTNER_GUILDS.remove(lobby.getId());
            LOGGER.warn("No available partner guild/category for full lobby {}", lobby.getId());
            sendDeletable(lobby.getLeaderID(),"Lobby.Voice.NoPartner.Title",
                    t(lobby.getLeaderID(),"Lobby.Voice.NoPartner.Description"));
            return;
        }
        attempted.add(target.guild.getIdLong());
        target.category.createVoiceChannel("MYG Lobby " + lobby.getId())
                .clearPermissionOverrides()
                .setUserlimit(Math.min(lobby.getMaxPlayers(), VoiceChannel.MAX_USERLIMIT))
                .addPermissionOverride(target.guild.getPublicRole(), EnumSet.noneOf(Permission.class),
                        EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT))
                .addPermissionOverride(target.guild.getSelfMember(),
                        EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT),
                        EnumSet.noneOf(Permission.class))
                .queue(channel -> {
            LobbyRepository.setVoiceChannel(lobby.getId(), target.guild.getIdLong(), channel.getIdLong());
            LobbyObject current = LobbyRepository.get(lobby.getId());
            if (current != null)
                channel.getManager().setUserLimit(Math.min(current.getMaxPlayers(), VoiceChannel.MAX_USERLIMIT))
                        .queue(null, error -> LOGGER.warn("Could not update user limit for lobby voice {}",
                                lobby.getId(), error));
            if (current != null && LobbyRepository.memberCount(current.getId()) < current.getMaxPlayers()) {
                LobbyRepository.reopenPreservingVoiceChannel(current.getId());
                PROVISIONING.remove(current.getId());
                ATTEMPTED_PARTNER_GUILDS.remove(current.getId());
                refreshManagementMessages(LobbyRepository.get(current.getId()));
                return;
            }
            grantVoicePermissionsSequentially(current == null ? lobby : current, channel, userIds, 0, 1);
        }, error -> {
                    LOGGER.error("Could not create voice channel for lobby {}", lobby.getId(), error);
                    PartnerGuildService.notifyManagerProblem(target.config,"PartnerProgram.Problems.VoiceCreate",
                            java.util.Map.of("%error%",errorLabel(error)));
                    tryPartnerGuild(lobby,userIds);
                });
    }

    private void grantVoicePermissionsSequentially(LobbyObject lobby, VoiceChannel channel,
                                                   List<Integer> userIds, int index, int verificationPass) {
        if (index >= userIds.size()) {
            CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(
                    () -> verifyAllVoicePermissions(lobby, channel, userIds, verificationPass));
            return;
        }
        UserObject user = UserController.get(userIds.get(index));
        if (user == null) {
            scheduleNextVoicePermission(lobby, channel, userIds, index, verificationPass);
            return;
        }
        channel.getGuild().retrieveMemberById(user.getDiscordID()).queue(
                member -> grantVoicePermission(lobby, channel, userIds, index, verificationPass, member, 1),
                error -> {
                    Member cached = channel.getGuild().getMemberById(user.getDiscordID());
                    if (cached != null)
                        grantVoicePermission(lobby, channel, userIds, index, verificationPass, cached, 1);
                    else
                        scheduleNextVoicePermission(lobby, channel, userIds, index, verificationPass);
                });
    }

    private void grantVoicePermission(LobbyObject lobby, VoiceChannel channel, List<Integer> userIds,
                                      int index, int verificationPass, Member member, int attempt) {
        channel.upsertPermissionOverride(member)
                .setPermissions(LOBBY_VOICE_PERMISSIONS, EnumSet.noneOf(Permission.class))
                .queue(override -> {
                    boolean confirmed = override.getAllowed().containsAll(LOBBY_VOICE_PERMISSIONS)
                            && java.util.Collections.disjoint(override.getDenied(), LOBBY_VOICE_PERMISSIONS);
                    if (confirmed) {
                        LOGGER.debug("Confirmed voice permission override for Discord user {} in lobby {}",
                                member.getId(), lobby.getId());
                        scheduleNextVoicePermission(lobby, channel, userIds, index, verificationPass);
                    } else if (attempt < PERMISSION_VERIFICATION_ATTEMPTS) {
                        CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() ->
                                grantVoicePermission(lobby, channel, userIds, index, verificationPass, member, attempt + 1));
                    } else {
                        failVoiceProvisioning(lobby, channel, member.getId());
                    }
                }, error -> {
                    if (attempt < PERMISSION_VERIFICATION_ATTEMPTS) {
                        LOGGER.warn("Voice permission update failed for Discord user {} in lobby {}; retrying",
                                member.getId(), lobby.getId(), error);
                        CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() ->
                                grantVoicePermission(lobby, channel, userIds, index, verificationPass, member, attempt + 1));
                    } else {
                        LOGGER.error("Voice permission update permanently failed for Discord user {} in lobby {}",
                                member.getId(), lobby.getId(), error);
                        failVoiceProvisioning(lobby, channel, member.getId());
                    }
                });
    }

    private void scheduleNextVoicePermission(LobbyObject lobby, VoiceChannel channel,
                                             List<Integer> userIds, int index, int verificationPass) {
        CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() ->
                grantVoicePermissionsSequentially(lobby, channel, userIds, index + 1, verificationPass));
    }

    private void verifyAllVoicePermissions(LobbyObject lobby, VoiceChannel channel,
                                           List<Integer> userIds, int verificationPass) {
        if (channel.getGuild().getVoiceChannelById(channel.getIdLong()) == null) {
            failPartnerGuild(lobby,channel,userIds,"PartnerProgram.Problems.ChannelMissing",java.util.Map.of());
            return;
        }
        List<Member> missing = userIds.stream().map(UserController::get).filter(Objects::nonNull)
                .map(user -> channel.getGuild().getMemberById(user.getDiscordID()))
                .filter(Objects::nonNull)
                .filter(member -> !hasRequiredVoicePermissions(channel, member)).toList();
        if (missing.isEmpty()) {
            createVoiceInvite(lobby, channel, userIds);
            return;
        }
        if (verificationPass >= PERMISSION_VERIFICATION_ATTEMPTS) {
            LOGGER.error("Voice permission verification failed for lobby {} after {} complete passes; affected Discord users: {}",
                    lobby.getId(), verificationPass, missing.stream().map(Member::getId).toList());
            failVoiceProvisioning(lobby, channel, missing.stream().map(Member::getId).toList().toString());
            return;
        }
        LOGGER.warn("Final voice permission check found {} missing users for lobby {}; starting full repair pass {}/{}",
                missing.size(), lobby.getId(), verificationPass + 1, PERMISSION_VERIFICATION_ATTEMPTS);
        grantVoicePermissionsSequentially(lobby, channel, userIds, 0, verificationPass + 1);
    }

    private static boolean hasRequiredVoicePermissions(VoiceChannel channel, Member member) {
        var override = channel.getPermissionOverride(member);
        return override != null
                && override.getAllowed().containsAll(LOBBY_VOICE_PERMISSIONS)
                && java.util.Collections.disjoint(override.getDenied(), LOBBY_VOICE_PERMISSIONS)
                && member.hasPermission(channel, LOBBY_VOICE_PERMISSIONS);
    }

    private void failVoiceProvisioning(LobbyObject lobby, VoiceChannel channel, String affectedDiscordUsers) {
        LOGGER.error("Aborting voice provisioning for lobby {} because permissions could not be confirmed for {}",
                lobby.getId(), affectedDiscordUsers);
        failPartnerGuild(lobby,channel,LobbyRepository.memberIds(lobby.getId()),
                "PartnerProgram.Problems.Permissions",java.util.Map.of("%users%",affectedDiscordUsers));
    }

    private void createVoiceInvite(LobbyObject lobby, VoiceChannel channel, List<Integer> userIds) {
        long guildId = channel.getGuild().getIdLong();
        boolean bypass = channel.getGuild().getSelfMember().hasPermission(Permission.KICK_MEMBERS);
        createLobbyInvite(lobby, channel, bypass).queue(
                invite -> completeVoiceInvite(lobby, channel, userIds, invite),
                error -> {
                    if (bypass && isUnsupportedApplicationBypass(error)) {
                        LOGGER.debug("Guild {} does not use member applications; retrying lobby {} with a regular invite",
                                guildId, lobby.getId());
                        createLobbyInvite(lobby, channel, false).queue(
                                invite -> completeVoiceInvite(lobby, channel, userIds, invite),
                                regularError -> failVoiceInvite(lobby, channel, userIds, regularError));
                        return;
                    }
                    failVoiceInvite(lobby, channel, userIds, error);
                });
    }

    private static boolean isUnsupportedApplicationBypass(Throwable error) {
        if (!(error instanceof ErrorResponseException response) || response.getErrorCode() != 50035) return false;
        return response.getSchemaErrors().stream()
                .filter(schema -> "flags".equals(schema.getLocation()))
                .flatMap(schema -> schema.getErrors().stream())
                .anyMatch(schemaError -> "GUILD_INVITE_CANNOT_CREATE_APPLICATION_BYPASS_INVITE"
                        .equals(schemaError.getCode()));
    }

    private RestAction<Invite> createLobbyInvite(LobbyObject lobby, VoiceChannel channel, boolean bypassApplication) {
        if (bypassApplication) {
            LOGGER.debug("Creating application-bypass invite for lobby {} on guild {}",
                    lobby.getId(), channel.getGuild().getId());
            return new ApplicationBypassInviteAction(jda, channel.getIdLong(),
                    Math.toIntExact(TimeUnit.MINUTES.toSeconds(6)), lobby.getMaxPlayers());
        }
        LOGGER.debug("Creating regular invite for lobby {} on guild {}",
                lobby.getId(), channel.getGuild().getId());
        return channel.createInvite().setMaxAge(6L, TimeUnit.MINUTES)
                .setMaxUses(lobby.getMaxPlayers()).setUnique(true);
    }

    private void completeVoiceInvite(LobbyObject lobby, VoiceChannel channel, List<Integer> userIds, Invite invite) {
        LobbyRepository.setVoiceInviteUrl(lobby.getId(), invite.getUrl());
        notifyVoiceReady(lobby, channel, userIds, invite.getUrl());
        refreshManagementMessages(LobbyRepository.get(lobby.getId()));
        PROVISIONING.remove(lobby.getId());
        ATTEMPTED_PARTNER_GUILDS.remove(lobby.getId());
    }

    private void failVoiceInvite(LobbyObject lobby, VoiceChannel channel, List<Integer> userIds, Throwable error) {
        LOGGER.warn("Could not create voice invite for lobby {}", lobby.getId(), error);
        failPartnerGuild(lobby,channel,userIds,"PartnerProgram.Problems.InviteCreate",
                java.util.Map.of("%error%",errorLabel(error)));
    }

    private void failPartnerGuild(LobbyObject lobby,VoiceChannel channel,List<Integer> userIds,
                                  String problemKey,java.util.Map<String,String> values){
        GuildObject configured=GuildRepository.get(channel.getGuild().getIdLong());
        PartnerGuildService.notifyManagerProblem(configured,problemKey,values);
        LobbyRepository.reopenAfterVoiceTimeout(lobby.getId());
        channel.delete().queue(unused->tryPartnerGuild(LobbyRepository.get(lobby.getId()),userIds),error->{
            LOGGER.warn("Could not delete failed voice channel for lobby {}",lobby.getId(),error);
            tryPartnerGuild(LobbyRepository.get(lobby.getId()),userIds);
        });
    }

    public void deleteVoiceChannel(LobbyObject lobby) {
        if (lobby == null || lobby.getVoiceChannelID() == 0) return;
        VoiceChannel channel = jda.getVoiceChannelById(lobby.getVoiceChannelID());
        if (channel != null) channel.delete().queue(null,
                error -> LOGGER.warn("Could not delete voice channel for lobby {}", lobby.getId()));
    }

    public void removeVoiceAccess(LobbyObject lobby, int userId) {
        if (lobby == null || lobby.getVoiceChannelID() == 0 || lobby.getGuildID() == 0) return;
        VoiceChannel channel = jda.getVoiceChannelById(lobby.getVoiceChannelID());
        Guild guild = jda.getGuildById(lobby.getGuildID());
        UserObject user = UserController.get(userId);
        if (channel == null || guild == null || user == null) return;
        Member member = guild.getMemberById(user.getDiscordID());
        if (member == null) return;
        if (member.getVoiceState() != null && member.getVoiceState().getChannel() != null
                && member.getVoiceState().getChannel().getIdLong() == channel.getIdLong())
            guild.kickVoiceMember(member).queue(null,
                    error -> LOGGER.warn("Could not disconnect user {} from lobby voice {}", userId, lobby.getId()));
        var override = channel.getPermissionOverride(member);
        if (override != null) override.delete().queue(null,
                error -> LOGGER.warn("Could not remove voice permission for user {} in lobby {}", userId, lobby.getId()));
    }

    public void notifyLobbyKick(int userId, int lobbyId, String reason) {
        sendDeletable(userId, "Lobby.Kick.Notification.Title", t(userId, "Lobby.Kick.Notification.Description",
                java.util.Map.of("%lobbyId%", String.valueOf(lobbyId), "%reason%", reason)));
    }

    public void refreshMainManagementMessage(int userId) {
        refreshManagementMessage(userId, null, false);
    }

    public void notifyHostPromotion(int userId, int lobbyId) {
        UserObject user = UserController.get(userId);
        if (user == null) return;
        jda.retrieveUserById(user.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
                dm.sendMessageEmbeds(new EmbedCreator().setTitle(t(userId, "Lobby.HostPromotion.Title"))
                        .setDescription(t(userId, "Lobby.HostPromotion.Description", java.util.Map.of(
                                "%lobbyId%", String.valueOf(lobbyId)))).build())
                        .setComponents(ActionRow.of(deleteButton(userId))).queue()));
    }

    public void refreshManagementMessages(LobbyObject lobby) {
        refreshPendingInvitations(lobby);
        refreshManagementMessages(lobby, true);
    }

    public void refreshMainManagementMessages(LobbyObject lobby) {
        refreshManagementMessages(lobby, false);
    }

    private void refreshManagementMessages(LobbyObject lobby, boolean showLobby) {
        if (lobby == null) return;
        for (int userId : LobbyRepository.memberIds(lobby.getId())) refreshManagementMessage(userId, lobby, showLobby);
    }

    private void refreshManagementMessage(int userId, LobbyObject lobby, boolean showLobby) {
        UserObject user = UserController.get(userId);
        if (user == null) return;
        jda.retrieveUserById(user.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
                dm.retrievePinnedMessages().limit(50).queue(pins -> pins.stream()
                        .filter(pin -> pin.getMessage().getAuthor().getIdLong() == jda.getSelfUser().getIdLong())
                        .max(Comparator.comparing(pin -> pin.getTimePinned()))
                        .ifPresent(pin -> {
                            UserControlManager manager = new UserControlManager(pin.getMessage(), user);
                            if (showLobby && lobby != null) manager.loadLobbyPage(lobby); else manager.loadStartPage();
                        }), error -> LOGGER.warn("Could not load management message for user {}", userId)),
                error -> LOGGER.warn("Could not open management DM for user {}", userId)));
    }

    public void notifyVoiceReminder(int userId, int lobbyId) {
        sendDeletable(userId, "Lobby.Voice.Reminder.Title", t(userId, "Lobby.Voice.Reminder.Description",
                java.util.Map.of("%lobbyId%", String.valueOf(lobbyId))));
    }

    public void notifyVoiceLeft(int userId, int lobbyId) {
        UserObject user = UserController.get(userId);
        if (user == null) return;
        jda.retrieveUserById(user.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
                dm.sendMessageEmbeds(new EmbedCreator().setTitle(t(userId, "Lobby.Voice.Rejoin.Title"))
                                .setDescription(t(userId, "Lobby.Voice.Rejoin.Description")).build())
                        .setComponents(ActionRow.of(
                                Button.secondary("voiceExtend-" + lobbyId, t(userId, "Lobby.Voice.Rejoin.Extend")),
                                Button.danger("delete", t(userId, "General.Button.DeleteMessage"))))
                        .queue()));
    }

    public void notifyPassiveQueueExhausted(LobbyObject lobby, LobbyObject mergeTarget) {
        int userId = lobby.getLeaderID();
        UserObject user = UserController.get(userId);
        if (user == null) return;
        int players = LobbyRepository.memberCount(lobby.getId());
        java.util.Map<String, String> replacements = new java.util.HashMap<>();
        replacements.put("%players%", String.valueOf(players));
        if (mergeTarget != null) {
            replacements.put("%game%", gameDisplayName(mergeTarget.getGameID()));
            replacements.put("%playerCount%", String.valueOf(LobbyRepository.memberCount(mergeTarget.getId())));
            replacements.put("%capacity%", String.valueOf(mergeTarget.getMaxPlayers()));
            replacements.put("%platform%", mergeTarget.getPlatform());
            replacements.put("%region%", mergeTarget.getRegion());
            replacements.put("%playerList%", invitationPlayers(mergeTarget, userId));
            replacements.put("%languages%", LobbyLanguageRepository.get(mergeTarget.getId()).stream()
                    .map(code -> CommunicationLanguageNames.displayName(code, user.getLanguage()))
                    .reduce((first, next) -> first + ", " + next)
                    .orElse(t(userId, "Lobby.View.AnyLanguage")));
            if (GameMessageVisibility.showsRanks(mergeTarget.getGameID()))
                replacements.put("%ranks%", compatibleRankLabel(mergeTarget, userId));
        }
        String descriptionKey = mergeTarget == null ? "Lobby.PassiveQueue.Exhausted.Description"
                : GameMessageVisibility.showsRanks(mergeTarget.getGameID())
                ? "Lobby.PassiveQueue.Exhausted.DescriptionWithLobby"
                : "Lobby.PassiveQueue.Exhausted.DescriptionWithLobbyNoRank";
        List<Button> buttons = new java.util.ArrayList<>();
        buttons.add(Button.success("lobbyStartCurrent-" + lobby.getId(),
                t(userId, "Lobby.PassiveQueue.StartCurrent")).withDisabled(players < 2));
        if (mergeTarget != null)
            buttons.add(Button.primary("lobbyFindMerge-" + lobby.getId(),
                    t(userId, "Lobby.PassiveQueue.FindMerge")));
        buttons.add(Button.danger("lobbyDissolve-" + lobby.getId(),
                t(userId, "Lobby.PassiveQueue.Dissolve")));
        buttons.add(deleteButton(userId));
        jda.retrieveUserById(user.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
                dm.sendMessageEmbeds(new EmbedCreator().setTitle(t(userId, "Lobby.PassiveQueue.Exhausted.Title"))
                                .setDescription(t(userId, descriptionKey, replacements)).build())
                        .setComponents(ActionRow.of(buttons)).queue()));
    }

    public boolean isPassiveQueueAvailable(int userId) {
        if (LobbyRepository.getActiveForUser(userId) != null) return false;
        PassiveQueueSettings settings = PassiveQueueSettingsRepository.get(userId);
        if (!settings.syncOnlineStatus()) return settings.enabled();
        UserObject user = UserController.get(userId);
        if (user == null) return false;
        User discordUser = jda.getUserById(user.getDiscordID());
        if (discordUser == null) return false;
        return jda.getMutualGuilds(discordUser).stream()
                .map(guild -> guild.getMemberById(user.getDiscordID())).filter(Objects::nonNull)
                .map(Member::getOnlineStatus)
                .anyMatch(status -> status == OnlineStatus.ONLINE || status == OnlineStatus.IDLE
                        || status == OnlineStatus.DO_NOT_DISTURB);
    }

    public void promptGameProfileUpdates(LobbyObject lobby) {
        String descriptionKey = GameMessageVisibility.profileVariantKey(
                "GameProfile.AfterLobby.Description", lobby.getGameID());
        for (int userId : LobbyRepository.memberIds(lobby.getId())) {
            UserObject user = UserController.get(userId);
            if (user == null) continue;
            jda.retrieveUserById(user.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
                    dm.sendMessageEmbeds(new EmbedCreator().setTitle(t(userId, "GameProfile.AfterLobby.Title"))
                                    .setDescription(t(userId, descriptionKey)).build())
                            .setComponents(ActionRow.of(
                                    Button.primary("gameProfileChanged-" + lobby.getGameID(), t(userId, "GameProfile.AfterLobby.Changed")),
                                    Button.secondary("gameProfileUnchanged-" + lobby.getGameID(), t(userId, "GameProfile.AfterLobby.Unchanged"))))
                            .queue()));
        }
    }

    public void notifyAutomaticClosure(LobbyObject lobby) {
        UserObject host = UserController.get(lobby.getLeaderID());
        if (host == null) return;
        jda.retrieveUserById(host.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
            dm.sendMessageEmbeds(Main.matchService.closureSummary(lobby.getId(), host.getId()))
                    .setComponents(Main.matchService.hostEntryComponents(lobby.getId(), host.getId()))
                    .queue(message -> Main.matchService.storeHostEntryMessage(lobby.getId(), message.getId()))));
    }

    private PartnerTarget selectPartnerGuild(LobbyObject lobby,Set<Long> excludedGuilds) {
        UserObject host = UserController.get(lobby.getLeaderID());
        long preferredGuild = host == null ? 0 : host.getCreateGuild();
        List<Integer> members = LobbyRepository.memberIds(lobby.getId());
        List<PartnerTarget> candidates=new java.util.ArrayList<>();
        for(GuildObject config:GuildRepository.getPartnerGuilds()){
            if(excludedGuilds.contains(config.getGuildID()))continue;
            Guild guild=jda.getGuildById(config.getGuildID());
            if(guild==null){
                excludedGuilds.add(config.getGuildID());
                PartnerGuildService.notifyManagerProblem(config,"PartnerProgram.Problems.GuildUnavailable",java.util.Map.of());
                continue;
            }
            Category category=guild.getCategoryById(config.getMygVoiceCategoryId());
            if(category==null){
                excludedGuilds.add(config.getGuildID());
                PartnerGuildService.notifyManagerProblem(config,"PartnerProgram.Problems.CategoryMissing",java.util.Map.of());
                continue;
            }
            long count=members.stream().map(UserController::get).filter(user->user!=null)
                    .filter(user->guild.getMemberById(user.getDiscordID())!=null).count();
            candidates.add(new PartnerTarget(config,guild,category,count,config.getGuildID()==preferredGuild));
        }
        List<PartnerTarget> targets=candidates.stream()
                .sorted(Comparator.comparingLong(PartnerTarget::memberCount)
                        .thenComparing(PartnerTarget::hostGuild).reversed()).toList();
        for(PartnerTarget target:targets){
            Set<Permission> missing=PartnerGuildService.missingPermissions(target.guild,target.category);
            if(missing.isEmpty())return target;
            excludedGuilds.add(target.guild.getIdLong());
            PartnerGuildService.notifyManagerProblem(target.config,"PartnerProgram.Problems.MissingPermissions",
                    java.util.Map.of("%permissions%",PartnerGuildService.permissionNames(missing,target.config.getLanguage())));
        }
        return null;
    }

    private void notifyVoiceReady(LobbyObject lobby, VoiceChannel voice, List<Integer> userIds, String inviteUrl) {
        for (int userId : userIds) {
            UserObject user = UserController.get(userId);
            if (user == null) continue;
            Member member = voice.getGuild().getMemberById(user.getDiscordID());
            String description = member == null
                    ? t(userId, "Lobby.Voice.ReadyExternal", java.util.Map.of(
                            "%invite%", inviteUrl == null ? t(userId, "Lobby.Voice.InviteUnavailable") : inviteUrl,
                            "%channel%", voice.getName()))
                    : t(userId, "Lobby.Voice.ReadyMember", java.util.Map.of("%channel%", voice.getAsMention()));
            jda.retrieveUserById(user.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
                    dm.sendMessageEmbeds(new EmbedCreator().setTitle(t(userId, "Lobby.Voice.Title")).setDescription(description).build())
                            .setComponents(ActionRow.of(Button.danger("delete", t(userId, "General.Button.DeleteMessage"))))
                            .queue(message -> LobbyRepository.storeVoiceReadyMessage(lobby.getId(), userId, message.getId()))));
        }
    }

    public void deleteVoiceReadyMessage(LobbyObject lobby, int userId) {
        String messageId = LobbyRepository.takeVoiceReadyMessage(lobby.getId(), userId);
        UserObject user = UserController.get(userId);
        if (messageId == null || user == null) return;
        jda.retrieveUserById(user.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
                dm.deleteMessageById(messageId).queue(null, ignored -> {})));
    }

    private void sendDeletable(int userId, String titleKey, String description) {
        UserObject user = UserController.get(userId);
        if (user == null) return;
        jda.retrieveUserById(user.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
                dm.sendMessageEmbeds(new EmbedCreator().setTitle(t(userId, titleKey)).setDescription(description).build())
                        .setComponents(ActionRow.of(Button.danger("delete", t(userId, "General.Button.DeleteMessage"))))
                        .queue()));
    }

    private static String errorLabel(Throwable error){return error==null?"-":error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
    private record PartnerTarget(GuildObject config,Guild guild, Category category, long memberCount, boolean hostGuild) {}

    private static net.dv8tion.jda.api.entities.MessageEmbed invitationEmbed(
            LobbyInvitation invitation, LobbyObject lobby, UserObject recipient) {
        UserObject leader = UserController.get(lobby.getLeaderID());
        java.util.Map<String, String> replacements = new java.util.HashMap<>();
        replacements.put("%leader%", leader == null ? "-" : leader.getUsername());
        replacements.put("%lobbyId%", String.valueOf(lobby.getId()));
        replacements.put("%game%", gameDisplayName(lobby.getGameID()));
        replacements.put("%platform%", lobby.getPlatform());
        replacements.put("%region%", lobby.getRegion());
        replacements.put("%capacity%", String.valueOf(lobby.getMaxPlayers()));
        replacements.put("%playerCount%", String.valueOf(LobbyRepository.memberCount(lobby.getId())));
        replacements.put("%players%", invitationPlayers(lobby, recipient.getId()));
        if (GameMessageVisibility.showsRanks(lobby.getGameID()))
            replacements.put("%ranks%", compatibleRankLabel(lobby, recipient.getId()));
        String languages = LobbyLanguageRepository.get(lobby.getId()).stream()
                .map(code -> CommunicationLanguageNames.displayName(code, recipient.getLanguage()))
                .reduce((first, next) -> first + ", " + next)
                .orElse(t(recipient.getId(), "Lobby.View.AnyLanguage"));
        replacements.put("%languages%", languages);
        replacements.put("%leaderRating%", leader == null ? t(recipient.getId(), "Rating.None")
                : ratingLabel(leader.getId(), recipient.getId()));
        replacements.put("%source%", t(recipient.getId(), "Lobby.Invitation.Source." + invitation.source().name()));
        return new EmbedCreator().setTitle(t(recipient.getId(), "Lobby.Invitation.Title"))
                .setDescription(t(recipient.getId(), GameMessageVisibility.showsRanks(lobby.getGameID())
                        ? "Lobby.Invitation.Description" : "Lobby.Invitation.DescriptionNoRank", replacements)).build();
    }

    private static ActionRow invitationControls(LobbyInvitation invitation, UserObject recipient) {
        boolean leavesParty = invitation.source()
                != de.flolang.matchyourgame.database.lobby.InvitationSource.PASSIVE_QUEUE
                && PartyRepository.getForUser(recipient.getId()) != null;
        return ActionRow.of(
                Button.success("lobbyInviteAccept-" + invitation.id(), t(recipient.getId(), leavesParty
                        ? "Lobby.Invitation.LeavePartyAndAccept" : "Lobby.Invitation.Accept")),
                Button.danger("lobbyInviteDecline-" + invitation.id(), t(recipient.getId(), "Lobby.Invitation.Decline")),
                deleteButton(recipient.getId()));
    }

    private static String compatibleRankLabel(LobbyObject lobby, int viewerId) {
        if (lobby.isRankRulesUnrestricted()) return t(viewerId, "Lobby.View.AnyRank");
        List<Integer> memberRanks = new java.util.ArrayList<>();
        for (int memberId : LobbyRepository.memberIds(lobby.getId())) {
            GameProfile profile = GameProfileRepository.getForGame(memberId, lobby.getGameID()).stream()
                    .filter(candidate -> candidate.platform().equalsIgnoreCase(lobby.getPlatform())
                            || candidate.platform().equalsIgnoreCase("ANY")
                            || lobby.getPlatform().equalsIgnoreCase("ANY"))
                    .findFirst().orElse(null);
            if (profile != null) memberRanks.add(profile.rankValue());
        }
        List<GameOption> ranks = de.flolang.matchyourgame.database.game.RankCompatibilityRepository
                .getCompatibleRanksForSources(lobby.getGameID(), memberRanks);
        if (lobby.getCustomRankMin() != null)
            ranks = ranks.stream().filter(rank -> rank.sortOrder() >= lobby.getCustomRankMin()).toList();
        if (lobby.getCustomRankMax() != null)
            ranks = ranks.stream().filter(rank -> rank.sortOrder() <= lobby.getCustomRankMax()).toList();
        return ranks.isEmpty() ? t(viewerId, "Lobby.Invitation.RankUnknown") : RankDisplayFormatter.format(ranks);
    }

    private static String invitationPlayers(LobbyObject lobby, int recipientId) {
        GameObject game = GameRepository.get(lobby.getGameID());
        boolean skillbased = game != null && game.isSkillbased();
        String players = LobbyRepository.memberIds(lobby.getId()).stream().map(UserController::get)
                .filter(Objects::nonNull).map(member -> {
                    String rating = ratingLabel(member.getId(), recipientId);
                    if (!skillbased) return "• " + member.getUsername() + " · ⭐ " + rating;
                    GameProfile profile = GameProfileRepository.getForGame(member.getId(), lobby.getGameID()).stream()
                            .filter(candidate -> candidate.platform().equalsIgnoreCase(lobby.getPlatform())
                                    || candidate.platform().equalsIgnoreCase("ANY")
                                    || lobby.getPlatform().equalsIgnoreCase("ANY"))
                            .findFirst().orElse(null);
                    String rank = profile == null ? t(recipientId, "Lobby.Invitation.RankUnknown")
                            : GameOptionRepository.get(lobby.getGameID(), GameOption.Type.RANK).stream()
                            .filter(option -> option.sortOrder() == profile.rankValue()).map(GameOption::name)
                            .findFirst().orElse(String.valueOf(profile.rankValue()));
                    return "• " + member.getUsername() + " · " + rank + " · ⭐ " + rating;
                }).reduce((first, next) -> first + "\n" + next).orElse("-");
        return players.length() > 2500 ? players.substring(0, 2497) + "..." : players;
    }

    private static String gameDisplayName(int gameId) {
        GameObject game = GameRepository.get(gameId);
        if (game == null) return "#" + gameId;
        GameObject parent = game.getSubGameFrom();
        return parent == null ? game.getName() : parent.getName() + " · " + game.getName();
    }

    private static String ratingLabel(int userId, int viewerId) {
        Double rating = ReviewRepository.averageRating(userId);
        return rating == null ? t(viewerId, "Rating.None")
                : RatingFormatter.stars(rating);
    }

    private static Button deleteButton(int userId) {
        return Button.danger("delete", t(userId, "General.Button.DeleteMessage"));
    }
    private static String t(int userId, String key) { return LanguageManager.getMessageForUser(key, userId); }
    private static String t(int userId, String key, java.util.Map<String, String> replacements) {
        return LanguageManager.getMessageForUser(key, userId, replacements);
    }
}
