package de.flolang.matchyourgame.manager;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;

public final class TutorialManager {
    private TutorialManager() {}

    public static void send(UserObject user) {
        if (user == null || Main.jda == null) return;
        Main.jda.retrieveUserById(user.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(
                channel -> send(channel, user)));
    }

    public static void send(PrivateChannel channel, UserObject user) {
        channel.sendMessageEmbeds(new EmbedCreator()
                        .setTitle(LanguageManager.getMessageForUser("Tutorial.Title", user.getId()))
                        .setDescription(LanguageManager.getMessageForUser("Tutorial.Description", user.getId())).build())
                .setComponents(ActionRow.of(Button.danger("delete",
                        LanguageManager.getMessageForUser("General.Button.DeleteMessage", user.getId())))).queue();
    }
}
