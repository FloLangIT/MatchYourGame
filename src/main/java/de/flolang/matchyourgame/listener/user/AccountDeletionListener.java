package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.language.Language;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.AccountDeletionService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.modals.Modal;

public final class AccountDeletionListener extends ListenerAdapter {
    public static void register(JDA jda) {
        jda.upsertCommand(Commands.slash("delete-account", "Eigenen MYG-Account anonymisieren")).queue();
    }

    @Override public void onButtonInteraction(ButtonInteractionEvent event) {
        if (!event.getComponentId().equals("profileDeleteAccount")) return;
        open(event.getUser().getIdLong(), modal -> event.replyModal(modal).queue(),
                () -> event.reply("No account found.").setEphemeral(true).queue());
    }

    @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("delete-account")) return;
        open(event.getUser().getIdLong(), modal -> event.replyModal(modal).queue(),
                () -> event.reply("No account found.").setEphemeral(true).queue());
    }

    @Override public void onModalInteraction(ModalInteractionEvent event) {
        if (!event.getModalId().equals("profileDeleteAccountConfirm")) return;
        UserObject user = UserRepository.get(event.getUser().getIdLong());
        if (user == null || user.isAnonymized()) { event.reply("No account found.").setEphemeral(true).queue(); return; }
        String confirmation = event.getValue("confirmation").getAsString().trim();
        if (!confirmation.equalsIgnoreCase("DELETE") && !confirmation.equalsIgnoreCase("LÖSCHEN")) {
            event.reply(t(user, "AccountDeletion.InvalidConfirmation")).setEphemeral(true).queue(); return;
        }
        boolean deleted = AccountDeletionService.anonymize(user);
        event.reply(t(user, deleted ? "AccountDeletion.Success" : "AccountDeletion.Failed"))
                .setEphemeral(true).queue();
        if (deleted && event.getMessage() != null) event.getMessage().delete().queue();
    }

    private static void open(long discordId, java.util.function.Consumer<Modal> consumer, Runnable missing) {
        UserObject user = UserRepository.get(discordId); // intentionally includes banned users
        if (user == null || user.isAnonymized()) { missing.run(); return; }
        consumer.accept(Modal.create("profileDeleteAccountConfirm", t(user, "AccountDeletion.Modal.Title"))
                .addComponents(Label.of(t(user, "AccountDeletion.Modal.Confirmation"),
                        TextInput.create("confirmation", TextInputStyle.SHORT).setRequired(true).setMaxLength(10).build()))
                .build());
    }

    private static String t(UserObject user, String key) {
        return LanguageManager.getMessageByLanguage(key, user == null ? Language.EN : user.getLanguage());
    }
}
