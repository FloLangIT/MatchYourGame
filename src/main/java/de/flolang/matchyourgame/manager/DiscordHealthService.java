package de.flolang.matchyourgame.manager;

import de.flolang.matchyourgame.database.guild.GuildObject;
import de.flolang.matchyourgame.database.guild.GuildRepository;
import de.flolang.matchyourgame.database.guild.GuildSetupAuthorizationRepository;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.language.Language;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class DiscordHealthService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordHealthService.class);
    private final JDA jda;

    public DiscordHealthService(JDA jda) {
        this.jda = Objects.requireNonNull(jda);
    }

    public void runStartupChecks() {
        for (GuildObject configured : GuildRepository.getAll()) {
            Guild guild = jda.getGuildById(configured.getGuildID());
            if (guild == null) {
                markGuildUnavailable(configured);
                continue;
            }
            if (!configured.isActive()) {
                handleGuildJoin(guild, () -> sendSetupRequestToOwner(guild));
                continue;
            }
            GuildRepository.setActive(configured.getGuildID(), true);
            checkPartnerGuild(guild);
            checkRegistration(guild);
        }
        reconcileAllUsers();
    }

    public void reconcileAllUsers() {
        for (UserObject user : UserRepository.getAll()) reconcileUser(user);
    }

    public void reconcileUser(UserObject user) {
        if (user == null || user.isAnonymized()) return;
        boolean sharesGuild = jda.getGuilds().stream()
                .anyMatch(guild -> guild.getMemberById(user.getDiscordID()) != null);
        if (UserRepository.setActive(user.getId(), sharesGuild))
            LOGGER.info("User {} is now {}", user.getId(), sharesGuild ? "active" : "inactive");
    }

    public void activateUser(long discordId) {
        UserObject user = UserRepository.get(discordId);
        if (user != null && UserRepository.setActive(user.getId(), true))
            LOGGER.info("User {} is active again after joining a mutual guild", user.getId());
    }

    public void handleGuildLeave(long guildId) {
        GuildObject configured = GuildRepository.get(guildId);
        if (configured != null) markGuildUnavailable(configured);
        reconcileAllUsers();
    }

    public void handleGuildJoin(Guild guild, Runnable setupRequired) {
        GuildObject configured = GuildRepository.get(guild.getIdLong());
        if (configured == null) {
            setupRequired.run();
            return;
        }
        validateExistingConfiguration(guild, configured, validation -> {
            if (validation == Validation.INVALID) {
                if (GuildRepository.deleteConfiguration(guild.getIdLong())) setupRequired.run();
                else LOGGER.error("Could not reset invalid configuration for rejoined guild {}", guild.getId());
                return;
            }
            if (validation == Validation.UNAVAILABLE) {
                GuildRepository.setActive(guild.getIdLong(), false);
                LOGGER.warn("Could not conclusively validate rejoined guild {}; keeping its configuration inactive",
                        guild.getId());
                return;
            }
            GuildRepository.setActive(guild.getIdLong(), true);
            checkPartnerGuild(guild);
            checkRegistration(guild);
            guild.getMembers().stream().map(member -> UserRepository.get(member.getIdLong()))
                    .filter(Objects::nonNull).forEach(user -> UserRepository.setActive(user.getId(), true));
            LOGGER.info("Reactivated existing configuration for guild {}", guild.getId());
        });
    }

    public void checkPartnerGuild(Guild guild) {
        GuildObject configured = GuildRepository.get(guild.getIdLong());
        if (configured == null || !configured.isPartnerGuild()) return;
        Category category = guild.getCategoryById(configured.getMygVoiceCategoryId());
        var missing = category == null ? EnumSet.copyOf(PartnerGuildService.REQUIRED_PERMISSIONS)
                : PartnerGuildService.missingPermissions(guild, category);
        boolean operational = category != null && missing.isEmpty();
        if (!GuildRepository.setPartnerOperational(configured.getGuildID(), operational)) return;
        if (operational) {
            notifyManager(configured, "PartnerProgram.Health.Restored", Map.of(), null);
        } else {
            String problem = category == null
                    ? LanguageManager.getMessageByLanguage("PartnerProgram.Problems.CategoryMissing", configured.getLanguage())
                    : LanguageManager.getMessageByLanguage("PartnerProgram.Problems.MissingPermissions", configured.getLanguage())
                            .replace("%permissions%", PartnerGuildService.permissionNames(missing, configured.getLanguage()));
            notifyManager(configured, "PartnerProgram.Health.Suspended", Map.of("%problem%", problem), null);
        }
    }

    public void checkRegistration(Guild guild) {
        GuildObject configured = GuildRepository.get(guild.getIdLong());
        if (configured == null) return;
        TextChannel channel = guild.getTextChannelById(configured.getMygTextChannelId());
        if (channel == null) {
            markRegistrationUnavailable(configured, 0, "GuildHealth.Registration.ChannelMissing");
            return;
        }
        if (!guild.getSelfMember().hasPermission(channel,
                Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY)) {
            markRegistrationUnavailable(configured, channel.getIdLong(),
                    "GuildHealth.Registration.PermissionsMissing");
            return;
        }
        if (configured.getMygTextMessageId() == 0) {
            findRegistrationMessage(channel,
                    message -> GuildRepository.setRegistrationTarget(configured.getGuildID(), channel.getIdLong(),
                            message.getIdLong(), true),
                    () -> markRegistrationUnavailable(configured, channel.getIdLong(),
                            "GuildHealth.Registration.MessageMissing"),
                    () -> LOGGER.warn("Could not inspect registration message history for guild {}",
                            configured.getGuildID()));
            return;
        }
        channel.retrieveMessageById(configured.getMygTextMessageId()).queue(message -> {
            if (isRegistrationMessage(message))
                GuildRepository.setRegistrationTarget(configured.getGuildID(), channel.getIdLong(), message.getIdLong(), true);
            else markRegistrationUnavailable(configured, channel.getIdLong(), "GuildHealth.Registration.MessageMissing");
        }, error -> {
            if (isDefinitelyMissing(error)) markRegistrationUnavailable(configured, channel.getIdLong(),
                    "GuildHealth.Registration.MessageMissing");
            else LOGGER.warn("Could not verify registration message for guild {}",
                    configured.getGuildID(), error);
        });
    }

    public void markRegistrationChannelDeleted(long guildId) {
        GuildObject configured = GuildRepository.get(guildId);
        if (configured != null) markRegistrationUnavailable(configured, 0, "GuildHealth.Registration.ChannelMissing");
    }

    public void markRegistrationMessageDeleted(long guildId) {
        GuildObject configured = GuildRepository.get(guildId);
        if (configured != null) markRegistrationUnavailable(configured, configured.getMygTextChannelId(),
                "GuildHealth.Registration.MessageMissing");
    }

    public void repairRegistration(long guildId, int managerUserId, Consumer<Boolean> result) {
        GuildObject configured = GuildRepository.get(guildId);
        Guild guild = jda.getGuildById(guildId);
        if (configured == null || guild == null || configured.getManagerUserId() != managerUserId) {
            result.accept(false);
            return;
        }
        TextChannel existing = guild.getTextChannelById(configured.getMygTextChannelId());
        if (existing != null && guild.getSelfMember().hasPermission(existing,
                Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY)) {
            createRegistrationMessage(existing, configured, result);
            return;
        }
        guild.createTextChannel(LanguageManager.getMessageByLanguage(
                        "NewGuild.SetupGuild.CreateProfileChannel", configured.getLanguage()))
                .addRolePermissionOverride(guild.getIdLong(), EnumSet.of(Permission.VIEW_CHANNEL),
                        EnumSet.of(Permission.MESSAGE_SEND))
                .addMemberPermissionOverride(jda.getSelfUser().getIdLong(),
                        EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY), null)
                .queue(channel -> createRegistrationMessage(channel, configured, result), error -> {
                    LOGGER.warn("Could not recreate registration channel for guild {}", guildId, error);
                    result.accept(false);
                });
    }

    public void storeRegistrationMessage(long guildId, long channelId, long messageId) {
        GuildRepository.setRegistrationTarget(guildId, channelId, messageId, true);
    }

    private void createRegistrationMessage(TextChannel channel, GuildObject configured, Consumer<Boolean> result) {
        channel.sendMessageEmbeds(LanguageManager.getEmbedByLanguage(
                        "CreateUser", configured.getLanguage(), new HashMap<>()).build())
                .setComponents(ActionRow.of(Button.primary("createAccount",
                        LanguageManager.getMessageByLanguage("CreateUser.Button", configured.getLanguage()))))
                .queue(message -> result.accept(GuildRepository.setRegistrationTarget(configured.getGuildID(),
                                channel.getIdLong(), message.getIdLong(), true)),
                        error -> { LOGGER.warn("Could not recreate registration message for guild {}",
                                configured.getGuildID(), error); result.accept(false); });
    }

    private void validateExistingConfiguration(Guild guild, GuildObject configured, Consumer<Validation> result) {
        if (UserRepository.get(configured.getManagerUserId()) == null) { result.accept(Validation.INVALID); return; }
        TextChannel channel = guild.getTextChannelById(configured.getMygTextChannelId());
        if (channel == null) { result.accept(Validation.INVALID); return; }
        Runnable validatePartner = () -> {
            if (!configured.isPartnerGuild()) { result.accept(Validation.VALID); return; }
            Category category = guild.getCategoryById(configured.getMygVoiceCategoryId());
            result.accept(category != null && PartnerGuildService.missingPermissions(guild, category).isEmpty()
                    ? Validation.VALID : Validation.INVALID);
        };
        if (configured.getMygTextMessageId() > 0) {
            channel.retrieveMessageById(configured.getMygTextMessageId()).queue(
                    message -> { if (isRegistrationMessage(message)) validatePartner.run();
                        else result.accept(Validation.INVALID); },
                    error -> result.accept(isDefinitelyMissing(error) ? Validation.INVALID : Validation.UNAVAILABLE));
        } else {
            findRegistrationMessage(channel, message -> {
                GuildRepository.setRegistrationTarget(configured.getGuildID(), channel.getIdLong(), message.getIdLong(), true);
                validatePartner.run();
            }, () -> result.accept(Validation.INVALID), () -> result.accept(Validation.UNAVAILABLE));
        }
    }

    private void findRegistrationMessage(TextChannel channel, Consumer<Message> found, Runnable missing,
                                         Runnable unavailable) {
        channel.getHistory().retrievePast(100).queue(messages -> messages.stream()
                        .filter(this::isRegistrationMessage).findFirst().ifPresentOrElse(found, missing),
                error -> unavailable.run());
    }

    private boolean isRegistrationMessage(Message message) {
        return message.getAuthor().getIdLong() == jda.getSelfUser().getIdLong()
                && message.getComponentTree().find(Button.class,
                        button -> "createAccount".equals(button.getCustomId())).isPresent();
    }

    private static boolean isDefinitelyMissing(Throwable error) {
        return error instanceof ErrorResponseException response
                && (response.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE
                || response.getErrorResponse() == ErrorResponse.UNKNOWN_CHANNEL);
    }

    private void markGuildUnavailable(GuildObject configured) {
        GuildRepository.setActive(configured.getGuildID(), false);
        if (configured.isPartnerGuild() && GuildRepository.setPartnerOperational(configured.getGuildID(), false))
            notifyManager(configured, "PartnerProgram.Health.Suspended",
                    Map.of("%problem%", LanguageManager.getMessageByLanguage(
                            "PartnerProgram.Problems.GuildUnavailable", configured.getLanguage())), null);
    }

    private void markRegistrationUnavailable(GuildObject configured, long channelId, String reasonKey) {
        boolean notify = configured.isRegistrationOperational();
        GuildRepository.setRegistrationTarget(configured.getGuildID(), channelId, 0, false);
        if (notify) notifyManager(configured, "GuildHealth.Registration",
                Map.of("%problem%", LanguageManager.getMessageByLanguage(reasonKey, configured.getLanguage())),
                Button.primary("guildRepairRegistration-" + configured.getGuildID(),
                        LanguageManager.getMessageByLanguage("GuildHealth.Registration.Recreate", configured.getLanguage())));
    }

    private void notifyManager(GuildObject configured, String messageKey,
                               Map<String, String> replacements, Button action) {
        UserObject manager = UserRepository.get(configured.getManagerUserId());
        if (manager == null) return;
        HashMap<String, String> values = new HashMap<>(replacements);
        Guild guild = jda.getGuildById(configured.getGuildID());
        values.put("%guild%", guild == null ? String.valueOf(configured.getGuildID()) : guild.getName());
        var embed = LanguageManager.getEmbedForUser(messageKey, manager.getId(), values).build();
        jda.retrieveUserById(manager.getDiscordID()).queue(user -> user.openPrivateChannel().queue(dm -> {
            var actionMessage = dm.sendMessageEmbeds(embed);
            if (action != null) actionMessage.setComponents(ActionRow.of(action));
            actionMessage.queue();
        }));
    }

    private void sendSetupRequestToOwner(Guild guild) {
        guild.retrieveOwner().queue(owner -> {
            GuildSetupAuthorizationRepository.authorize(guild.getIdLong(), owner.getIdLong());
            UserObject user = UserRepository.get(owner.getIdLong());
            owner.getUser().openPrivateChannel().queue(dm -> {
                HashMap<String, String> values = new HashMap<>(Map.of("%guildName%", guild.getName()));
                if (user == null) {
                    dm.sendMessageEmbeds(LanguageManager.getEmbedByLanguage(
                                    "NewGuild.NoAccountYet", Language.EN, values).build())
                            .setComponents(ActionRow.of(
                                    Button.success("createAccount-setupGuild-" + guild.getIdLong(),
                                            LanguageManager.getMessageByLanguage("CreateUser.Button", Language.EN)),
                                    Button.danger("transferGuildManage-" + guild.getIdLong(),
                                            LanguageManager.getMessageByLanguage(
                                                    "NewGuild.NoAccountYet.Button.TransferManage", Language.EN))))
                            .queue();
                } else {
                    dm.sendMessageEmbeds(LanguageManager.getEmbedForUser(
                                    "NewGuild.SetupGuild", user.getId(), values).build())
                            .setComponents(ActionRow.of(Button.secondary("setupGuild-" + guild.getIdLong(),
                                    LanguageManager.getMessageForUser("NewGuild.SetupGuild.Button", user.getId()))))
                            .queue();
                }
            });
        }, error -> LOGGER.error("Could not request a fresh setup for guild {}", guild.getId(), error));
    }

    private enum Validation { VALID, INVALID, UNAVAILABLE }
}
