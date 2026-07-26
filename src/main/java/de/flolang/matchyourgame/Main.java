package de.flolang.matchyourgame;

import de.flolang.matchyourgame.config.ConfigManager;
import de.flolang.matchyourgame.database.Database;
import de.flolang.matchyourgame.database.friend.FriendRepository;
import de.flolang.matchyourgame.database.game.GameRepository;
import de.flolang.matchyourgame.database.game.GameStatDefinition;
import de.flolang.matchyourgame.database.game.GameStatDefinitionParser;
import de.flolang.matchyourgame.database.game.GameStatRepository;
import de.flolang.matchyourgame.database.game.GameOptionRepository;
import de.flolang.matchyourgame.database.game.RankCompatibilityRepository;
import de.flolang.matchyourgame.database.gameapi.GameApiRepository;
import de.flolang.matchyourgame.database.profile.GameProfileRepository;
import de.flolang.matchyourgame.database.profile.CommunicationLanguageRepository;
import de.flolang.matchyourgame.database.guild.GuildRepository;
import de.flolang.matchyourgame.database.guild.PartnerGuildApplicationRepository;
import de.flolang.matchyourgame.database.guild.GuildSetupAuthorizationRepository;
import de.flolang.matchyourgame.database.lobby.LobbyRepository;
import de.flolang.matchyourgame.database.review.ReviewRepository;
import de.flolang.matchyourgame.database.report.ReportRepository;
import de.flolang.matchyourgame.database.report.BanRepository;
import de.flolang.matchyourgame.database.report.WarningRepository;
import de.flolang.matchyourgame.database.lobby.PassiveQueueSettingsRepository;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.database.user.InboxMessageRepository;
import de.flolang.matchyourgame.database.tutorial.TutorialSessionRepository;
import de.flolang.matchyourgame.listener.guild.GuildJoinListener;
import de.flolang.matchyourgame.listener.guild.DiscordHealthListener;
import de.flolang.matchyourgame.listener.guild.SetupGuildListener;
import de.flolang.matchyourgame.listener.guild.LobbyVoiceListener;
import de.flolang.matchyourgame.listener.guild.LobbyGuildMemberListener;
import de.flolang.matchyourgame.listener.AuditLogListener;
import de.flolang.matchyourgame.logging.DiscordErrorAppender;
import de.flolang.matchyourgame.logging.DiscordLogService;
import de.flolang.matchyourgame.listener.guild.ReportModerationListener;
import de.flolang.matchyourgame.listener.user.CrewMenuListener;
import de.flolang.matchyourgame.listener.guild.AdminSlashCommandListener;
import de.flolang.matchyourgame.listener.user.*;
import de.flolang.matchyourgame.manager.lobby.LobbyDiscordCoordinator;
import de.flolang.matchyourgame.manager.lobby.LobbyService;
import de.flolang.matchyourgame.manager.match.MatchService;
import de.flolang.matchyourgame.manager.gameapi.GameApiService;
import de.flolang.matchyourgame.manager.gameapi.GameApiOAuthServer;
import de.flolang.matchyourgame.manager.review.ReviewService;
import de.flolang.matchyourgame.manager.lobby.GameSelectionWizard;
import de.flolang.matchyourgame.manager.DiscordHealthService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private final String VERSION = "V0.0.01";

    public static JDA jda;
    public static final java.time.Instant STARTED_AT = java.time.Instant.now();
    public static LobbyService lobbyService;
    public static ReviewService reviewService;
    public static MatchService matchService;
    public static GameApiService gameApiService;
    public static GameApiOAuthServer gameApiOAuthServer;
    public static GameSelectionWizard gameSelectionWizard;
    public static de.flolang.matchyourgame.manager.party.PartyService partyService;
    public static DiscordHealthService healthService;
    private final ScheduledExecutorService lobbyScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "lobby-invitation-waves");
        thread.setDaemon(true);
        return thread;
    });

    public Main() throws InterruptedException {
        new ConfigManager();
        jda = JDABuilder.createDefault(ConfigManager.getString("Discord.Token"), GatewayIntent.getIntents(-1))
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .setChunkingFilter(ChunkingFilter.ALL)
                .enableCache(CacheFlag.ONLINE_STATUS, CacheFlag.MEMBER_OVERRIDES)
                .setStatus(OnlineStatus.ONLINE)
                .setActivity(Activity.customStatus(ConfigManager.getString("Discord.Activity").replace("%version%", VERSION)))
                .build()
                .awaitReady();

        DiscordLogService.initialize(jda);
        DiscordErrorAppender.install();

        //Database connection
        LOGGER.debug("Try to connect to database");
        Database.connect(ConfigManager.getString("SQL.JDBC"), ConfigManager.getString("SQL.Username"), ConfigManager.getString("SQL.Password"));
        UserRepository.init();
        InboxMessageRepository.init();
        GuildRepository.init();
        GuildSetupAuthorizationRepository.init();
        PartnerGuildApplicationRepository.init();
        UserRepository.setFK();
        GuildRepository.setFK();
        FriendRepository.init();
        GameRepository.init();
        GameRepository.setFK();
        GameStatRepository.init();
        GameOptionRepository.init();
        RankCompatibilityRepository.init();
        GameProfileRepository.init();
        CommunicationLanguageRepository.init();
        TutorialSessionRepository.init();
        LobbyRepository.init();
        GameApiRepository.init();
        PassiveQueueSettingsRepository.init();
        ReportRepository.init();
        BanRepository.init();
        WarningRepository.init();
        healthService = new DiscordHealthService(jda);

        lobbyService = new LobbyService(new LobbyDiscordCoordinator(jda));
        reviewService = new ReviewService(jda);
        matchService = new MatchService(jda);
        gameApiService = new GameApiService();
        gameApiService.autoMapAllRanks();
        gameApiOAuthServer = new GameApiOAuthServer(gameApiService);
        gameApiOAuthServer.start();
        gameSelectionWizard = new GameSelectionWizard();
        partyService = new de.flolang.matchyourgame.manager.party.PartyService();
        lobbyScheduler.scheduleWithFixedDelay(() -> {
            try {
                lobbyService.processInvitationWaves();
                partyService.processInactiveParties();
                matchService.processDeadlines();
            } catch (RuntimeException exception) {
                LOGGER.error("Scheduled lobby and match processing failed", exception);
            }
        }, 1, 1, TimeUnit.MINUTES);

        registerListeners();
        healthService.runStartupChecks();
        GuildRepository.getAll().forEach(guild ->
                de.flolang.matchyourgame.manager.PartnerGuildService.checkEligibility(guild.getGuildID()));
        AdminSlashCommandListener.register(jda);
        ClearChatCommandListener.register(jda);
        TutorialCommandListener.register(jda);
        AccountDeletionListener.register(jda);

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
                        lobbyScheduler.shutdownNow();
                        jda.shutdown();
                        System.exit(0);
                        break;
                    case "reload":
                        LOGGER.info("Reloading configuration...");
                        new ConfigManager();
                        break;
                    case "game":
                        if(args.length >= 5 && args[1].equalsIgnoreCase("create")) {
                            // game create NAME MAINGAME_ID SKILLBASED [PLAYER|TEAM:NAME:TYPE ...]
                            String name = args[2];
                            int maingame;
                            try {
                                maingame = Integer.parseInt(args[3]);
                            } catch (NumberFormatException e) {
                                LOGGER.warn("Invalid maingame ID: {}", args[3]);
                                break;
                            }
                            boolean skillbased = Boolean.parseBoolean(args[4]);
                            List<GameStatDefinition> statistics;
                            try {
                                statistics = GameStatDefinitionParser.parse(String.join(" ",
                                        java.util.Arrays.copyOfRange(args, 5, args.length)));
                            } catch (IllegalArgumentException exception) {
                                LOGGER.warn(exception.getMessage());
                                break;
                            }
                            GameRepository.create(maingame, name, skillbased, true, statistics);
                            LOGGER.info("Game {} created successfully", name);
                        } else {
                            LOGGER.info("Use game create NAME MAINGAME_ID SKILLBASED [PLAYER|TEAM:NAME:TYPE ...]");
                        }
                        break;
                    case "review":
                        if (args.length == 2 && args[1].equalsIgnoreCase("pending")) {
                            List<ReviewRepository.ModerationReview> pending = ReviewRepository.pendingModeration();
                            if (pending.isEmpty()) LOGGER.info("No reviews are waiting for moderation");
                            for (ReviewRepository.ModerationReview review : pending) {
                                LOGGER.info("Review {} | lobby {} | target {} | B/T/R {}/{}/{} | feedback: {}",
                                        review.assignmentId(), review.lobbyId(), review.targetUserId(), review.behaviorStars(),
                                        review.teamplayStars(), review.reliabilityStars(), review.privateFeedback());
                            }
                        } else if (args.length == 4 && args[1].equalsIgnoreCase("moderate")) {
                            try {
                                int assignmentId = Integer.parseInt(args[2]);
                                int stars = Integer.parseInt(args[3]);
                                LOGGER.info(ReviewRepository.moderate(assignmentId, stars)
                                        ? "Review {} moderated with {} stars" : "Review moderation failed", assignmentId, stars);
                            } catch (NumberFormatException e) {
                                LOGGER.warn("Use review moderate ASSIGNMENT_ID STARS_1_TO_5");
                            }
                        } else {
                            LOGGER.info("Use review pending OR review moderate ASSIGNMENT_ID STARS_1_TO_5");
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
        jda.addEventListener(new AuditLogListener());
        jda.addEventListener(new GuildJoinListener(healthService));
        jda.addEventListener(new DiscordHealthListener(healthService));
        jda.addEventListener(new CreateUserListener());
        jda.addEventListener(new SetupGuildListener());
        jda.addEventListener(new GuildManagerListener());
        jda.addEventListener(new UserProfileButtonListener());
        jda.addEventListener(new InboxListener());
        jda.addEventListener(new ProfileEditListener());
        jda.addEventListener(new AccountDeletionListener());
        jda.addEventListener(new AddFriendListener());
        jda.addEventListener(new DeleteMessageListener());
        jda.addEventListener(new FriendRequestHandleListener());
        jda.addEventListener(new LobbyInteractionListener());
        jda.addEventListener(new FriendMenuListener());
        jda.addEventListener(new FriendActivityListener());
        jda.addEventListener(new ClearChatCommandListener());
        jda.addEventListener(new TutorialCommandListener());
        jda.addEventListener(new TutorialInteractionListener());
        jda.addEventListener(new ReportListener());
        jda.addEventListener(new ProjectInviteListener());
        jda.addEventListener(new ProjectUserAdminListener());
        jda.addEventListener(new ProjectGuildAdminListener());
        jda.addEventListener(new PartnerGuildProgramListener());
        jda.addEventListener(new AdminPanelListener());
        jda.addEventListener(new CrewMenuListener());
        jda.addEventListener(new PartyMenuListener());
        jda.addEventListener(new LobbyLanguageJoinListener());
        jda.addEventListener(new PassiveProfileListener());
        jda.addEventListener(new VoiceControlListener());
        jda.addEventListener(new LobbyAdminListener());
        jda.addEventListener(new LobbyVoiceListener());
        jda.addEventListener(new LobbyGuildMemberListener());
        jda.addEventListener(new ReportModerationListener());
        jda.addEventListener(new AdminSlashCommandListener());
        jda.addEventListener(gameSelectionWizard);
    }

    public static void main(String[] args) throws InterruptedException {
        new Main();
    }

}
