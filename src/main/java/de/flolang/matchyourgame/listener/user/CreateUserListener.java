package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.config.ConfigManager;
import de.flolang.matchyourgame.database.guild.GuildController;
import de.flolang.matchyourgame.database.guild.GuildObject;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.database.user.InboxMessageRepository;
import de.flolang.matchyourgame.database.report.BanRepository;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.Language;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.UserControlManager;
import de.flolang.matchyourgame.manager.InboxService;
import de.flolang.matchyourgame.manager.TutorialManager;
import de.flolang.matchyourgame.logging.DiscordLogService;
import de.flolang.matchyourgame.manager.PartnerGuildService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class CreateUserListener extends ListenerAdapter {

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (!event.getButton().getCustomId().startsWith("createAccount")) {
            return;
        }
        boolean validProjectInvitation = event.getButton().getCustomId()
                .equals("createAccount-projectInvite-" + event.getUser().getIdLong());
        if(Objects.equals(ConfigManager.getString("Testing.Enabled"), "true") && !validProjectInvitation) {
            Guild guildById = event.getJDA().getGuildById(ConfigManager.getString("Discord.MYGGuildID"));
            if(guildById == null || !guildById.retrieveMemberById(event.getUser().getIdLong()).complete().getRoles().contains(guildById.getRoleById(ConfigManager.getString("Testing.RoleID")))) {
                event.replyEmbeds(LanguageManager.getEmbedByLanguage("CreateUser.AccountCreationRestricted", event.getGuild() == null ? Language.EN : GuildController.getByGuildID(event.getGuild().getIdLong()).getLanguage(), new HashMap<>()).build()).setEphemeral(true).queue();
                return;
            }
        }
        UserObject userObject = UserRepository.get(event.getUser().getIdLong());
        if(userObject != null) {
            event.replyEmbeds(LanguageManager.getEmbedForUser("CreateUser.YouAlreadyHaveAccount", userObject.getId(), new HashMap<>()).build()).setEphemeral(true).queue();
            return;
        }
        if (BanRepository.isDiscordBanned(event.getUser().getIdLong())) {
            event.reply(LanguageManager.getMessageByLanguage("CreateUser.Banned", Language.EN)).setEphemeral(true).queue();
            return;
        }

        Language language = Language.EN;
        if(event.getGuild() != null) {
            GuildObject guildObject = GuildController.getByGuildID(event.getGuild().getIdLong());
            if (guildObject != null) {
                language = guildObject.getLanguage();
            }
        }

        Modal modal = Modal.create("createAccount" + event.getButton().getCustomId().replace("createAccount", ""), LanguageManager.getMessageByLanguage("CreateUser.Modal.Title", language))
                .addComponents(Label.of(LanguageManager.getMessageByLanguage("CreateUser.Modal.Username", language),
                        TextInput.create("username", TextInputStyle.SHORT)
                                .setRequiredRange(4, 30)
                                .setRequired(true)
                                .build()))
                .addComponents(Label.of(LanguageManager.getMessageByLanguage("CreateUser.Modal.Language", language),
                        StringSelectMenu.create("language")
                                .addOption("English", "en", "", Emoji.fromFormatted("🇺🇸"))
                                .addOption("German/Deutsch", "de", "", Emoji.fromFormatted("🇩🇪"))
                                .setRequiredRange(1,1)
                                .setRequired(true)
                                .build())).build();

        event.replyModal(modal).queue();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if(!event.getModalId().startsWith("createAccount")) {
            return;
        }

        String[] args = event.getModalId().replace("createAccount-", "").split("-");

        UserObject userObject = UserRepository.get(event.getUser().getIdLong());
        if(userObject != null) {
            event.replyEmbeds(LanguageManager.getEmbedForUser("CreateUser.YouAlreadyHaveAccount", userObject.getId(), new HashMap<>()).build()).setEphemeral(true).queue();
            return;
        }
        if (BanRepository.isDiscordBanned(event.getUser().getIdLong())) {
            event.reply(LanguageManager.getMessageByLanguage("CreateUser.Banned", Language.EN)).setEphemeral(true).queue();
            return;
        }
        Language language = Language.valueOf(Objects.requireNonNull(event.getValue("language")).getAsStringList().getFirst().toUpperCase());
        // During first-time guild setup the guild row does not exist yet, so the foreign key
        // can only be assigned after SetupGuildListener has created that row.
        boolean pendingGuildSetup = args.length == 2 && args[0].equals("setupGuild");
        long accountCreateGuild = pendingGuildSetup || event.getGuild() == null
                ? 0 : event.getGuild().getIdLong();
        event.getUser().openPrivateChannel().queue(privateChannel -> {
            UserObject createdUser = UserController.create(event.getValue("username").getAsString(),
                    event.getUser().getIdLong(), language, accountCreateGuild);
            if (createdUser != null) DiscordLogService.action("ACCOUNT_CREATED",
                    "MYG-Account " + createdUser.getUsername() + " (#" + createdUser.getId()
                            + ") für Discord-User " + event.getUser().getName()
                            + " (" + event.getUser().getId() + ") erstellt");
            if (createdUser != null && createdUser.getCreateGuild() > 0)
                PartnerGuildService.checkEligibility(createdUser.getCreateGuild());
            event.replyEmbeds(LanguageManager.getEmbedForUser("CreateUser.AccountCreated", createdUser.getId(), new HashMap<>()).build()).setEphemeral(true).queue();

            InboxService.sendToUser(createdUser, createdUser,
                    LanguageManager.getMessageForUser("Inbox.Welcome.Title", createdUser.getId()),
                    LanguageManager.getMessageForUser("Inbox.Welcome.Description", createdUser.getId()),
                    InboxMessageRepository.DeliveryMode.SILENT);

            //Send user private control panel
            privateChannel.sendMessageEmbeds(new EmbedCreator().setTitle("Loading...").build()).queue(message -> {
                new UserControlManager(message, createdUser).loadStartPage();
                TutorialManager.send(privateChannel, createdUser);
            });
            if(args.length == 2 && args[0].equals("setupGuild")) {
                if (event.getMessage() != null) event.getMessage().delete().queue(ignored -> {}, ignored -> {});
                HashMap<String, String> replacings = new HashMap<>();
                Guild guild = event.getJDA().getGuildById(args[1]);
                if(guild != null) {
                    replacings.put("%guildName%", guild.getName());
                    privateChannel.sendMessageEmbeds(LanguageManager.getEmbedForUser("NewGuild.SetupGuild", createdUser.getId(), replacings).build())
                            .addComponents(ActionRow.of(Button.secondary("setupGuild-" + guild.getId(),
                                    LanguageManager.getMessageForUser("NewGuild.SetupGuild.Button", createdUser.getId()))))
                            .queueAfter(5, TimeUnit.SECONDS);
                }
            }
            if (args.length == 2 && args[0].equals("projectInvite") && event.getMessage() != null)
                event.getMessage().delete().queue(ignored -> {}, ignored -> {});
        },throwable -> {
            event.replyEmbeds(LanguageManager.getEmbedByLanguage("CreateUser.PrivateChatDisable", language, new HashMap<>()).build()).setEphemeral(true).queue();
        });
    }
}
