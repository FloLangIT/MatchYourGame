package de.flolang.matchyourgame.listener.guild;

import de.flolang.matchyourgame.database.lobby.LobbyObject;
import de.flolang.matchyourgame.database.lobby.LobbyRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class LobbyGuildMemberListener extends ListenerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(LobbyGuildMemberListener.class);
    private static final EnumSet<Permission> LOBBY_VOICE_PERMISSIONS = EnumSet.of(
            Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK);

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        LobbyObject lobby = LobbyRepository.getActiveForUser(user.getId());
        if (lobby == null || lobby.getGuildID() != event.getGuild().getIdLong()
                || lobby.getVoiceChannelID() == 0
                || !LobbyRepository.memberIds(lobby.getId()).contains(user.getId())) return;
        VoiceChannel channel = event.getGuild().getVoiceChannelById(lobby.getVoiceChannelID());
        if (channel != null) grantAndVerify(channel, event.getMember(), lobby.getId(), user.getId(), 1);
    }

    private static void grantAndVerify(VoiceChannel channel, net.dv8tion.jda.api.entities.Member member,
                                       int lobbyId, int userId, int attempt) {
        channel.upsertPermissionOverride(member)
                .setPermissions(LOBBY_VOICE_PERMISSIONS, EnumSet.noneOf(Permission.class))
                .queue(ignored -> CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS)
                                .execute(() -> verify(channel, member, lobbyId, userId, attempt)),
                        error -> retryOrLog(channel, member, lobbyId, userId, attempt, error));
    }

    private static void verify(VoiceChannel channel, net.dv8tion.jda.api.entities.Member member,
                               int lobbyId, int userId, int attempt) {
        var override = channel.getPermissionOverride(member);
        boolean valid = override != null
                && override.getAllowed().containsAll(LOBBY_VOICE_PERMISSIONS)
                && Collections.disjoint(override.getDenied(), LOBBY_VOICE_PERMISSIONS)
                && member.hasPermission(channel, LOBBY_VOICE_PERMISSIONS);
        if (valid) {
            LobbyObject lobby = LobbyRepository.get(lobbyId);
            if (lobby != null) member.getUser().openPrivateChannel().queue(dm -> dm.sendMessageEmbeds(
                    new EmbedCreator().setTitle(LanguageManager.getMessageForUser("Lobby.Voice.GuildJoined.Title", userId))
                            .setDescription(LanguageManager.getMessageForUser("Lobby.Voice.GuildJoined.Description", userId,
                                    Map.of("%channel%", channel.getAsMention(), "%invite%",
                                            lobby.getVoiceInviteUrl() == null ? "-" : lobby.getVoiceInviteUrl())))
                            .build()).queue());
            return;
        }
        retryOrLog(channel, member, lobbyId, userId, attempt, null);
    }

    private static void retryOrLog(VoiceChannel channel, net.dv8tion.jda.api.entities.Member member,
                                   int lobbyId, int userId, int attempt, Throwable error) {
        if (attempt >= 3) {
            LOGGER.error("Could not verify voice permissions for Discord user {} in lobby {} after {} attempts",
                    member.getId(), lobbyId, attempt, error);
            return;
        }
        if (error != null) LOGGER.warn("Voice permission update failed for Discord user {} in lobby {}; retrying",
                member.getId(), lobbyId, error);
        CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS)
                .execute(() -> grantAndVerify(channel, member, lobbyId, userId, attempt + 1));
    }
}
