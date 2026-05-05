package de.flolang.matchyourgame.listener.guild;

import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.language.Language;
import de.flolang.matchyourgame.language.LanguageManager;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.HashMap;

public class GuildJoinListener extends ListenerAdapter {

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        Guild guild = event.getGuild();
        User user = guild.getOwner().getUser();
        UserObject userObject = UserController.get(user.getIdLong());
        user.openPrivateChannel().queue(privateChannel -> {
            //Check if owner already have an account to setup own guild
            HashMap<String, String> replacings = new HashMap<>();
            replacings.put("%guildName%", guild.getName());
            if(userObject == null) {
                privateChannel.sendMessageEmbeds(LanguageManager.getEmbedByLanguage("NewGuild.NoAccountYet", Language.EN, replacings).build())
                        .addComponents(ActionRow.of(Button.success("createAccount-setupGuild-" + event.getGuild().getIdLong(), LanguageManager.getMessageByLanguage("CreateUser.Button", Language.EN)), Button.danger("transferGuildManage-" + event.getGuild().getIdLong(), LanguageManager.getMessageByLanguage("NewGuild.NoAccountYet.Button.TransferManage", Language.EN)))).queue();
            } else {
                privateChannel.sendMessageEmbeds(LanguageManager.getEmbedForUser("NewGuild.SetupGuild", userObject.getId(), replacings).build()).addComponents(ActionRow.of(Button.secondary("setupGuild-" + event.getGuild().getIdLong(), LanguageManager.getMessageForUser("NewGuild.SetupGuild.Button", userObject.getId())))).queue();
            }
        });
    }
}
