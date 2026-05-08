package de.flolang.matchyourgame;

import de.flolang.matchyourgame.config.ConfigManager;
import de.flolang.matchyourgame.database.Database;
import de.flolang.matchyourgame.database.friend.FriendRepository;
import de.flolang.matchyourgame.database.guild.GuildController;
import de.flolang.matchyourgame.database.guild.GuildRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.language.Language;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.listener.guild.GuildJoinListener;
import de.flolang.matchyourgame.listener.guild.SetupGuildListener;
import de.flolang.matchyourgame.listener.user.AddFriendListener;
import de.flolang.matchyourgame.listener.user.CreateUserListener;
import de.flolang.matchyourgame.listener.user.UserProfileButtonListener;
import de.flolang.matchyourgame.manager.UserControlManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;

public class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private final String VERSION = "DEV-0.1";

    public static JDA jda;

    public Main() throws InterruptedException {
        new ConfigManager();
        jda = JDABuilder.createDefault(ConfigManager.getString("Discord.Token"), GatewayIntent.getIntents(-1))
                .setStatus(OnlineStatus.ONLINE)
                .setActivity(Activity.customStatus(ConfigManager.getString("Discord.Activity").replace("%version%", VERSION)))
                .build()
                .awaitReady();

        //Database connection
        LOGGER.debug("Try to connect to database");
        Database.connect(ConfigManager.getString("SQL.JDBC"), ConfigManager.getString("SQL.Username"), ConfigManager.getString("SQL.Password"));
        UserRepository.init();
        GuildRepository.init();
        UserRepository.setFK();
        GuildRepository.setFK();
        FriendRepository.init();

        registerListeners();

        LOGGER.info("Bot is ready as {}", jda.getSelfUser().getAsTag());
    }


    private void registerListeners() {
        jda.addEventListener(new GuildJoinListener());
        jda.addEventListener(new CreateUserListener());
        jda.addEventListener(new SetupGuildListener());
        jda.addEventListener(new UserProfileButtonListener());
        jda.addEventListener(new AddFriendListener());
    }

    public static void main(String[] args) throws InterruptedException {
        new Main();
    }

}
