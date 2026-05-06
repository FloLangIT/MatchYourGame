package de.flolang.matchyourgame;

import de.flolang.matchyourgame.config.ConfigManager;
import de.flolang.matchyourgame.database.Database;
import de.flolang.matchyourgame.database.guild.GuildRepository;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.listener.guild.GuildJoinListener;
import de.flolang.matchyourgame.listener.guild.SetupGuildListener;
import de.flolang.matchyourgame.listener.user.CreateUserListener;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        registerListeners();

        LOGGER.info("Bot is ready as {}", jda.getSelfUser().getAsTag());
    }

    private void registerListeners() {
        jda.addEventListener(new GuildJoinListener());
        jda.addEventListener(new CreateUserListener());
        jda.addEventListener(new SetupGuildListener());
    }

    public static void main(String[] args) throws InterruptedException {
        new Main();
    }

}
