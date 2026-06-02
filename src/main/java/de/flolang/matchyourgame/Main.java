package de.flolang.matchyourgame;

import de.flolang.matchyourgame.config.ConfigManager;
import de.flolang.matchyourgame.database.Database;
import de.flolang.matchyourgame.database.friend.FriendRepository;
import de.flolang.matchyourgame.database.game.GameRepository;
import de.flolang.matchyourgame.database.guild.GuildController;
import de.flolang.matchyourgame.database.guild.GuildRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.language.Language;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.listener.guild.GuildJoinListener;
import de.flolang.matchyourgame.listener.guild.SetupGuildListener;
import de.flolang.matchyourgame.listener.user.*;
import de.flolang.matchyourgame.manager.FriendRequestManager;
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
import java.util.Scanner;

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
        GameRepository.init();
        GameRepository.setFK();

        registerListeners();

        LOGGER.info("Bot is ready as {}", jda.getSelfUser().getAsTag());

        consoleListener();
    }

    private void consoleListener() {
        Thread consoleThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);

            while (true) {
                String line = scanner.nextLine();
                String[] args = line.split(" ");

                switch (args[0].toLowerCase()) {
                    case "stop":
                    case "shutdown":
                        LOGGER.info("Shutting down the bot...");
                        jda.shutdown();
                        System.exit(0);
                        break;
                    case "reload":
                        LOGGER.info("Reloading configuration...");
                        new ConfigManager();
                        break;
                    case "game":
                        if(args.length == 5 && args[1].equalsIgnoreCase("create")) {
                            //game create $NAME $MAINGAME_ID(0=MAINGAME) $SKILLBASED
                            String name = args[2];
                            int maingame;
                            try {
                                maingame = Integer.parseInt(args[3]);
                            } catch (NumberFormatException e) {
                                LOGGER.warn("Invalid maingame ID: {}", args[3]);
                                break;
                            }
                            boolean skillbased = Boolean.parseBoolean(args[4]);
                            GameRepository.create(maingame, name, skillbased, true);
                            LOGGER.info("Game {} created successfully", name);
                        } else {
                            LOGGER.info("Use game create $NAME $MAINGAME_ID $SKILLBASED");
                        }
                        break;
                    default:
                        LOGGER.warn("Unknown command: {}", args[0]);
                }
            }
        });

        consoleThread.setDaemon(true);
        consoleThread.start();
    }

    private void registerListeners() {
        jda.addEventListener(new GuildJoinListener());
        jda.addEventListener(new CreateUserListener());
        jda.addEventListener(new SetupGuildListener());
        jda.addEventListener(new UserProfileButtonListener());
        jda.addEventListener(new AddFriendListener());
        jda.addEventListener(new DeleteMessageListener());
        jda.addEventListener(new FriendRequestHandleListener());
    }

    public static void main(String[] args) throws InterruptedException {
        new Main();
    }

}
