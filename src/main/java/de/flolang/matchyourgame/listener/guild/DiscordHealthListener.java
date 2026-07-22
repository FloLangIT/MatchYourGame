package de.flolang.matchyourgame.listener.guild;

import de.flolang.matchyourgame.database.guild.GuildObject;
import de.flolang.matchyourgame.database.guild.GuildRepository;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.language.Language;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.DiscordHealthService;
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.events.guild.override.GenericPermissionOverrideEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent;
import net.dv8tion.jda.api.events.role.update.RoleUpdatePermissionsEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class DiscordHealthListener extends ListenerAdapter {
    private final DiscordHealthService health;

    public DiscordHealthListener(DiscordHealthService health) {
        this.health = health;
    }

    @Override public void onGuildLeave(GuildLeaveEvent event) {
        long guildId = event.getGuild().getIdLong();
        CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(() -> health.handleGuildLeave(guildId));
    }

    @Override public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        if (!event.getUser().isBot()) health.activateUser(event.getUser().getIdLong());
    }

    @Override public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        if (event.getUser().isBot()) return;
        UserObject user = UserRepository.get(event.getUser().getIdLong());
        if (user != null) CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS)
                .execute(() -> health.reconcileUser(user));
    }

    @Override public void onGuildMemberRoleAdd(GuildMemberRoleAddEvent event) {
        if (event.getMember().getIdLong() == event.getJDA().getSelfUser().getIdLong()) {
            health.checkPartnerGuild(event.getGuild());
            health.checkRegistration(event.getGuild());
        }
    }

    @Override public void onGuildMemberRoleRemove(GuildMemberRoleRemoveEvent event) {
        if (event.getMember().getIdLong() == event.getJDA().getSelfUser().getIdLong()) {
            health.checkPartnerGuild(event.getGuild());
            health.checkRegistration(event.getGuild());
        }
    }

    @Override public void onRoleUpdatePermissions(RoleUpdatePermissionsEvent event) {
        var self = event.getGuild().getSelfMember();
        if (event.getRole().isPublicRole() || self.getRoles().contains(event.getRole())) {
            health.checkPartnerGuild(event.getGuild());
            health.checkRegistration(event.getGuild());
        }
    }

    @Override public void onGenericPermissionOverride(GenericPermissionOverrideEvent event) {
        GuildObject configured = GuildRepository.get(event.getGuild().getIdLong());
        if (configured != null && configured.isPartnerGuild()
                && event.getChannel().getIdLong() == configured.getMygVoiceCategoryId())
            health.checkPartnerGuild(event.getGuild());
        if (configured != null && event.getChannel().getIdLong() == configured.getMygTextChannelId())
            health.checkRegistration(event.getGuild());
    }

    @Override public void onChannelDelete(ChannelDeleteEvent event) {
        GuildObject configured = GuildRepository.get(event.getGuild().getIdLong());
        if (configured == null) return;
        if (event.getChannel().getIdLong() == configured.getMygTextChannelId())
            health.markRegistrationChannelDeleted(configured.getGuildID());
        if (configured.isPartnerGuild() && event.getChannel().getIdLong() == configured.getMygVoiceCategoryId())
            health.checkPartnerGuild(event.getGuild());
    }

    @Override public void onMessageDelete(MessageDeleteEvent event) {
        if (!event.isFromGuild()) return;
        GuildObject configured = GuildRepository.get(event.getGuild().getIdLong());
        if (configured != null && event.getMessageIdLong() == configured.getMygTextMessageId())
            health.markRegistrationMessageDeleted(configured.getGuildID());
    }

    @Override public void onMessageBulkDelete(MessageBulkDeleteEvent event) {
        GuildObject configured = GuildRepository.get(event.getGuild().getIdLong());
        if (configured != null && event.getMessageIds().contains(String.valueOf(configured.getMygTextMessageId())))
            health.markRegistrationMessageDeleted(configured.getGuildID());
    }

    @Override public void onButtonInteraction(ButtonInteractionEvent event) {
        if (!event.getComponentId().startsWith("guildRepairRegistration-")) return;
        long guildId;
        try { guildId = Long.parseLong(event.getComponentId().substring("guildRepairRegistration-".length())); }
        catch (NumberFormatException exception) { return; }
        UserObject manager = UserRepository.get(event.getUser().getIdLong());
        Language language = manager == null ? Language.EN : manager.getLanguage();
        event.deferReply(true).queue(hook -> health.repairRegistration(guildId,
                manager == null ? 0 : manager.getId(), success -> hook.editOriginal(
                        LanguageManager.getMessageByLanguage(success
                                ? "GuildHealth.Registration.Recreated"
                                : "GuildHealth.Registration.RecreateFailed", language)).queue()));
    }
}
