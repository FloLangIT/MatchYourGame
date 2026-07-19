package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.Language;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.UserControlManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ClearChatCommandListener extends ListenerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClearChatCommandListener.class);

    public static void register(JDA jda) {
        jda.upsertCommand(Commands.slash("clear", "Bot-Chat leeren und Management-Nachricht neu erstellen"))
                .queue(command -> LOGGER.info("Registered global /clear command"),
                        error -> LOGGER.error("Could not register /clear", error));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("clear")) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) {
            event.reply(LanguageManager.getMessageByLanguage("Clear.NoAccount", Language.EN))
                    .setEphemeral(true).queue();
            return;
        }
        if (event.getGuild() != null) {
            event.reply(LanguageManager.getMessageForUser("Clear.PrivateOnly", user.getId()))
                    .setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue(hook -> event.getUser().openPrivateChannel().queue(dm -> {
            List<CompletableFuture<?>> deletions = new CopyOnWriteArrayList<>();
            dm.getIterableHistory().forEachAsync(message -> {
                if (message.getAuthor().getIdLong() == event.getJDA().getSelfUser().getIdLong())
                    deletions.add(message.delete().submit().exceptionally(error -> null));
                return true;
            }).handle((unused, error) -> {
                if (error != null) LOGGER.warn("Could not read complete DM history for user {}", user.getId(), error);
                return null;
            }).thenCompose(unused -> CompletableFuture.allOf(deletions.toArray(CompletableFuture[]::new)))
                    .whenComplete((unused, error) -> dm.sendMessageEmbeds(
                                    new EmbedCreator().setTitle("Loading...").build())
                            .queue(message -> {
                                new UserControlManager(message, user).loadStartPage();
                                hook.deleteOriginal().queue();
                            }, sendError -> LOGGER.error(
                                    "Could not recreate management message for user {}", user.getId(), sendError)));
        }, error -> hook.editOriginal(LanguageManager.getMessageForUser("Clear.Failed", user.getId())).queue()));
    }
}
