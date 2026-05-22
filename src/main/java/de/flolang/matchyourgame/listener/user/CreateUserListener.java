package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.config.ConfigManager;
import de.flolang.matchyourgame.database.guild.GuildController;
import de.flolang.matchyourgame.database.guild.GuildObject;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.Language;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.UserControlManager;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;

public class CreateUserListener extends ListenerAdapter {

    public HashMap<Long, Message> guildSetupCreating = new HashMap<>();

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (!event.getButton().getCustomId().startsWith("createAccount")) {
            return;
        }
        if(Objects.equals(ConfigManager.getString("Testing.Enabled"), "true")) {
            Guild guildById = event.getJDA().getGuildById(ConfigManager.getString("Discord.MYGGuildID"));
            if(guildById == null || !guildById.retrieveMemberById(event.getUser().getIdLong()).complete().getRoles().contains(guildById.getRoleById(ConfigManager.getString("Testing.RoleID")))) {
                event.replyEmbeds(LanguageManager.getEmbedByLanguage("CreateUser.AccountCreationRestricted", event.getGuild() == null ? Language.EN : GuildController.getByGuildID(event.getGuild().getIdLong()).getLanguage(), new HashMap<>()).build()).setEphemeral(true).queue();
                return;
            }
        }
        if(event.getButton().getCustomId().contains("-setupGuild-"))
            guildSetupCreating.put(event.getUser().getIdLong(), event.getMessage());
        UserObject userObject = UserController.get(event.getUser().getIdLong());
        if(userObject != null) {
            event.replyEmbeds(LanguageManager.getEmbedForUser("CreateUser.YouAlreadyHaveAccount", userObject.getId(), new HashMap<>()).build()).setEphemeral(true).queue();
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

        UserObject userObject = UserController.get(event.getUser().getIdLong());
        if(userObject != null) {
            event.replyEmbeds(LanguageManager.getEmbedForUser("CreateUser.YouAlreadyHaveAccount", userObject.getId(), new HashMap<>()).build()).setEphemeral(true).queue();
            if(guildSetupCreating.containsKey(event.getUser().getIdLong())) {
                guildSetupCreating.get(event.getUser().getIdLong()).delete().queue();
                guildSetupCreating.remove(event.getUser().getIdLong());
            }
            return;
        }
        Language language = Language.valueOf(Objects.requireNonNull(event.getValue("language")).getAsStringList().getFirst().toUpperCase());
        event.getUser().openPrivateChannel().queue(privateChannel -> {
            UserObject createdUser = UserController.create(event.getValue("username").getAsString(), event.getUser().getIdLong(), language, event.getGuild() != null ? event.getGuild().getIdLong() : 0);
            event.replyEmbeds(LanguageManager.getEmbedForUser("CreateUser.AccountCreated", createdUser.getId(), new HashMap<>()).build()).setEphemeral(true).queue();

            //Send user private control panel
            privateChannel.sendMessageEmbeds(new EmbedCreator().setTitle("Loading...").build()).queue(message -> {
                privateChannel.pinMessageById(message.getId()).queue(unused -> {
                    privateChannel.getHistory().retrievePast(1).queue(history -> {
                        history.get(0).delete().queue();
                    });
                });
                new UserControlManager(message, createdUser).loadStartPage();
            });
            if(args.length == 2 && args[0].equals("setupGuild")) {
                if(guildSetupCreating.containsKey(event.getUser().getIdLong())) {
                    guildSetupCreating.get(event.getUser().getIdLong()).delete().queue();
                    guildSetupCreating.remove(event.getUser().getIdLong());
                    HashMap<String, String> replacings = new HashMap<>();
                    Guild guild = event.getJDA().getGuildById(args[1]);
                    if(guild != null) {
                        replacings.put("%guildName%", guild.getName());
                        privateChannel.sendMessageEmbeds(LanguageManager.getEmbedForUser("NewGuild.SetupGuild", createdUser.getId(), replacings).build()).addComponents(ActionRow.of(Button.secondary("setupGuild-" + guild.getId(), LanguageManager.getMessageForUser("NewGuild.SetupGuild.Button", createdUser.getId())))).queue();
                    }
                }
            }
        },throwable -> {
            event.replyEmbeds(LanguageManager.getEmbedByLanguage("CreateUser.PrivateChatDisable", language, new HashMap<>()).build()).setEphemeral(true).queue();
        });
    }
}
