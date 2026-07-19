package de.flolang.matchyourgame.listener.guild;

import de.flolang.matchyourgame.config.ConfigManager;
import de.flolang.matchyourgame.database.guild.GuildController;
import de.flolang.matchyourgame.database.guild.GuildObject;
import de.flolang.matchyourgame.database.guild.GuildRepository;
import de.flolang.matchyourgame.database.guild.GuildSetupAuthorizationRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.language.Language;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.logging.DiscordLogService;
import de.flolang.matchyourgame.manager.PartnerGuildService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Objects;

public class SetupGuildListener extends ListenerAdapter {

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (!event.getButton().getCustomId().startsWith("setupGuild-")) {
            return;
        }
        long guildID = Long.parseLong(event.getButton().getCustomId().split("-")[1]);
        Guild guild = event.getJDA().getGuildById(guildID);
        UserObject userObject = UserController.get(event.getUser().getIdLong());
        if (userObject == null || GuildRepository.get(guildID) != null
                || !GuildSetupAuthorizationRepository.claimIfAbsent(guildID, event.getUser().getIdLong())) {
            event.reply(LanguageManager.getMessageByLanguage("GuildManager.Transfer.NotAuthorized",
                    userObject == null ? Language.EN : userObject.getLanguage())).setEphemeral(true).queue();
            return;
        }
        if (guild == null) {
            event.replyEmbeds(LanguageManager.getEmbedForUser("NewGuild.GuildNotFound", userObject.getId(), new HashMap<>()).build()).setEphemeral(true).queue();
            return;
        }
        Modal modal = Modal.create("setupGuild-" + event.getButton().getCustomId().replace("setupGuild-", ""), LanguageManager.getMessageForUser("NewGuild.SetupGuild.Modal.Title", userObject.getId()).replace("%guildName%", guild.getName()))
                .addComponents(Label.of(LanguageManager.getMessageForUser("NewGuild.SetupGuild.Modal.Language", userObject.getId()),
                        StringSelectMenu.create("language")
                                .addOption("English", "en", "", Emoji.fromFormatted("🇺🇸"))
                                .addOption("German/Deutsch", "de", "", Emoji.fromFormatted("🇩🇪"))
                                .setRequiredRange(1, 1)
                                .setRequired(true)
                                .build())).build();

        event.replyModal(modal).queue();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!event.getModalId().startsWith("setupGuild-")) {
            return;
        }
        String[] args = event.getModalId().replace("setupGuild-", "").split("-");

        Guild guild = event.getJDA().getGuildById(args[0]);
        UserObject userObject = UserController.get(event.getUser().getIdLong());
        if (userObject == null || GuildRepository.get(Long.parseLong(args[0])) != null
                || !GuildSetupAuthorizationRepository.isAuthorized(Long.parseLong(args[0]), event.getUser().getIdLong())) {
            event.reply(LanguageManager.getMessageByLanguage("GuildManager.Transfer.NotAuthorized",
                    userObject == null ? Language.EN : userObject.getLanguage())).setEphemeral(true).queue();
            return;
        }
        Language language = Language.valueOf(Objects.requireNonNull(event.getValue("language")).getAsStringList().getFirst().toUpperCase());
        if(guild == null) {
            event.replyEmbeds(LanguageManager.getEmbedForUser("NewGuild.GuildNotFound", userObject.getId(), new HashMap<>()).build()).setEphemeral(true).queue();
            return;
        }
        try {
            guild.createTextChannel(LanguageManager.getMessageByLanguage("NewGuild.SetupGuild.CreateProfileChannel", language)).addRolePermissionOverride(guild.getIdLong(), EnumSet.of(Permission.VIEW_CHANNEL), EnumSet.of(Permission.MESSAGE_SEND)).addMemberPermissionOverride(event.getJDA().getSelfUser().getIdLong(), EnumSet.of(Permission.MESSAGE_SEND), null).queue(textChannel -> {
                GuildObject guildObject = GuildController.create(guild.getIdLong(), userObject.getId(), 0, textChannel.getIdLong(), language);
                GuildSetupAuthorizationRepository.delete(guild.getIdLong());
                DiscordLogService.action("GUILD_SETUP", "Guild " + guild.getName() + " (" + guild.getId()
                        + ") wurde durch " + userObject.getUsername() + " (#" + userObject.getId()
                        + ") eingerichtet · Sprache " + language.name() + " · Account-Channel " + textChannel.getId());
                de.flolang.matchyourgame.database.user.UserRepository.assignCreateGuildIfMissing(
                        userObject.getId(), guild.getIdLong());
                if (userObject.getCreateGuild() == 0) {
                    userObject.setCreateGuild(guild.getIdLong());
                    de.flolang.matchyourgame.database.user.UserCache.put(userObject);
                }
                PartnerGuildService.checkEligibility(guild.getIdLong());
                textChannel.sendMessageEmbeds(LanguageManager.getEmbedByLanguage("CreateUser", language, new HashMap<>()).build()).addComponents(ActionRow.of(Button.primary("createAccount", LanguageManager.getMessageByLanguage("CreateUser.Button", language)))).queue(message -> {
                    HashMap<String, String> replacings = new HashMap<>();
                    replacings.put("%textChannel%", "<#" + textChannel.getId() + ">");
                    replacings.put("%partnerGuildUserCount%", String.valueOf(ConfigManager.getInt(
                            "PartnerGuildUserCreations", ConfigManager.getInt("CreatedUserToBecomePartnerGuild",30))));
                    replacings.put("%discordInvite%", ConfigManager.getString("Discord.SupportGuildInvite"));
                    event.replyEmbeds(LanguageManager.getEmbedForUser("NewGuild.SetupCompleted", userObject.getId(), replacings).build()).setEphemeral(true).queue();
                    event.getChannel().asPrivateChannel().getHistory().retrievePast(10).queue(messages -> {
                        messages.forEach(mess -> {
                            if (!mess.isPinned() && mess.getAuthor().isBot())
                                mess.delete().queue();
                        });
                    });
                });
            });
        } catch (InsufficientPermissionException e) {
            event.replyEmbeds(LanguageManager.getEmbedForUser("NewGuild.MissingPermissions", userObject.getId(), new HashMap<>()).build()).setEphemeral(true).queue();
        }
    }
}
