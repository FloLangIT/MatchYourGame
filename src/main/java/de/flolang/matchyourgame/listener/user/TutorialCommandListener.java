package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.language.Language;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.TutorialManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TutorialCommandListener extends ListenerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(TutorialCommandListener.class);

    public static void register(JDA jda) {
        jda.upsertCommand(Commands.slash("tutorial", "Tutorial in der bestehenden Management-Nachricht starten"))
                .queue(command -> LOGGER.info("Registered global /tutorial command"),
                        error -> LOGGER.error("Could not register /tutorial", error));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("tutorial")) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) {
            event.reply(LanguageManager.getMessageByLanguage("Tutorial.NoAccount", Language.EN))
                    .setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue(hook -> TutorialManager.restartExisting(user, result ->
                hook.editOriginal(LanguageManager.getMessageForUser(switch (result) {
                    case STARTED -> "Tutorial.Sent";
                    case ACTIVE_PARTY -> "Tutorial.ActiveParty";
                    case ACTIVE_LOBBY -> "Tutorial.ActiveLobby";
                    case MESSAGE_NOT_FOUND -> "Tutorial.ManagementMessageMissing";
                }, user.getId())).queue()));
    }
}
