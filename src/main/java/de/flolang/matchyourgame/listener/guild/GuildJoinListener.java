package de.flolang.matchyourgame.listener.guild;

import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.guild.GuildSetupAuthorizationRepository;
import de.flolang.matchyourgame.language.Language;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.DiscordHealthService;
import net.dv8tion.jda.api.audit.ActionType;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class GuildJoinListener extends ListenerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(GuildJoinListener.class);
    private static final int AUDIT_LOG_ATTEMPTS = 3;
    private final DiscordHealthService health;

    public GuildJoinListener(DiscordHealthService health) {
        this.health = health;
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        Guild guild = event.getGuild();
        health.handleGuildJoin(guild, () -> resolveInstaller(guild, Instant.now(), 1));
    }

    private void resolveInstaller(Guild guild, Instant joinedAt, int attempt) {
        guild.retrieveAuditLogs().type(ActionType.BOT_ADD).limit(5).queue(entries -> {
            var installerEntry = entries.stream()
                    .filter(entry -> entry.getTargetIdLong() == guild.getSelfMember().getIdLong())
                    .filter(entry -> !entry.getTimeCreated().toInstant().isBefore(joinedAt.minusSeconds(15)))
                    .findFirst();
            if (installerEntry.isEmpty()) {
                retryOrUseOwner(guild, joinedAt, attempt);
                return;
            }

            User installer = installerEntry.get().getUser();
            if (installer != null) {
                sendSetupMessage(guild, installer);
                return;
            }
            guild.getJDA().retrieveUserById(installerEntry.get().getUserIdLong()).queue(
                    user -> sendSetupMessage(guild, user),
                    error -> retryOrUseOwner(guild, joinedAt, attempt));
        }, error -> {
            LOGGER.warn("Could not read the audit log to resolve the installer of guild {}", guild.getId(), error);
            useGuildOwner(guild);
        });
    }

    private void retryOrUseOwner(Guild guild, Instant joinedAt, int attempt) {
        if (attempt >= AUDIT_LOG_ATTEMPTS) {
            LOGGER.warn("No recent BOT_ADD audit-log entry found for guild {}; falling back to the guild owner",
                    guild.getId());
            useGuildOwner(guild);
            return;
        }
        CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS)
                .execute(() -> resolveInstaller(guild, joinedAt, attempt + 1));
    }

    private void useGuildOwner(Guild guild) {
        guild.retrieveOwner().queue(member -> sendSetupMessage(guild, member.getUser()),
                error -> LOGGER.error("Could not resolve an installer or owner for guild {}", guild.getId(), error));
    }

    private void sendSetupMessage(Guild guild, User user) {
        GuildSetupAuthorizationRepository.authorize(guild.getIdLong(), user.getIdLong());
        UserObject userObject = UserController.get(user.getIdLong());
        user.openPrivateChannel().queue(privateChannel -> {
            HashMap<String, String> replacings = new HashMap<>();
            replacings.put("%guildName%", guild.getName());
            if(userObject == null) {
                privateChannel.sendMessageEmbeds(LanguageManager.getEmbedByLanguage("NewGuild.NoAccountYet", Language.EN, replacings).build())
                        .addComponents(ActionRow.of(Button.success("createAccount-setupGuild-" + guild.getIdLong(), LanguageManager.getMessageByLanguage("CreateUser.Button", Language.EN)), Button.danger("transferGuildManage-" + guild.getIdLong(), LanguageManager.getMessageByLanguage("NewGuild.NoAccountYet.Button.TransferManage", Language.EN)))).queue();
            } else {
                privateChannel.sendMessageEmbeds(LanguageManager.getEmbedForUser("NewGuild.SetupGuild", userObject.getId(), replacings).build()).addComponents(ActionRow.of(Button.secondary("setupGuild-" + guild.getIdLong(), LanguageManager.getMessageForUser("NewGuild.SetupGuild.Button", userObject.getId())))).queue();
            }
        }, error -> LOGGER.warn("Could not send the guild setup message for guild {} to user {}",
                guild.getId(), user.getId(), error));
    }
}
