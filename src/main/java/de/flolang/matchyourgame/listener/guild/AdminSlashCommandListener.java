package de.flolang.matchyourgame.listener.guild;

import de.flolang.matchyourgame.database.game.GameObject;
import de.flolang.matchyourgame.database.game.GameRepository;
import de.flolang.matchyourgame.database.game.GameStatDefinition;
import de.flolang.matchyourgame.database.game.GameStatDefinitionParser;
import de.flolang.matchyourgame.database.game.GameOption;
import de.flolang.matchyourgame.database.game.GameOptionRepository;
import de.flolang.matchyourgame.database.game.RankCompatibilityRepository;
import de.flolang.matchyourgame.database.review.ReviewRepository;
import de.flolang.matchyourgame.database.guild.GuildObject;
import de.flolang.matchyourgame.database.guild.GuildRepository;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.language.Language;
import de.flolang.matchyourgame.language.LanguageManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class AdminSlashCommandListener extends ListenerAdapter {
    public static final long ADMIN_GUILD_ID = 1174411600917176350L;
    private static final Logger LOGGER = LoggerFactory.getLogger(AdminSlashCommandListener.class);
    private static final DefaultMemberPermissions ADMIN_ONLY =
            DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR);

    public static void register(JDA jda) {
        Guild guild = jda.getGuildById(ADMIN_GUILD_ID);
        if (guild == null) {
            LOGGER.warn("Admin commands were not registered: bot is not on guild {}", ADMIN_GUILD_ID);
            return;
        }

        var game = Commands.slash("game", "Games verwalten")
                .setDefaultPermissions(ADMIN_ONLY)
                .addSubcommands(new SubcommandData("create", "Ein neues Game mit Statistikfeldern erstellen")
                        .addOption(OptionType.STRING, "name", "Name des Games", true)
                        .addOption(OptionType.INTEGER, "main_game", "0 für Hauptgame, sonst ID des Hauptgames", true)
                        .addOption(OptionType.BOOLEAN, "skillbased", "Besitzt das Game ein Rangsystem?", true)
                        .addOption(OptionType.STRING, "statistics",
                                "z. B. PLAYER:Kills:INTEGER TEAM:Score:INTEGER", false)
                        .addOption(OptionType.STRING, "platforms", "Kommagetrennt, z. B. PC,PS5,Xbox", false)
                        .addOption(OptionType.STRING, "regions", "Kommagetrennt, z. B. EU,NA,Asia", false)
                        .addOption(OptionType.STRING, "ranks", "Aufsteigend und kommagetrennt, z. B. Bronze,Silber,Gold", false)
                        .addOption(OptionType.STRING, "roles", "Optionale Rollen, z. B. Tank,Support,DPS", false)
                        .addOption(OptionType.STRING, "rank_groups", "z. B. Iron|Bronze|Silver;Silver|Gold", false)
                        .addOption(OptionType.INTEGER, "unrestricted_party_size", "Partygröße ohne Ranglimit, z. B. 5", false),
                        new SubcommandData("configure", "Auswahlwerte eines bestehenden Games oder Modus ändern")
                                .addOption(OptionType.INTEGER, "game_id", "ID des Games oder Modus", true)
                                .addOption(OptionType.STRING, "platforms", "Kommagetrennt; - zum Leeren", false)
                                .addOption(OptionType.STRING, "regions", "Kommagetrennt; - zum Leeren", false)
                                .addOption(OptionType.STRING, "ranks", "Aufsteigend, kommagetrennt; - zum Leeren", false)
                                .addOption(OptionType.STRING, "roles", "Kommagetrennt; - zum Leeren", false)
                                .addOption(OptionType.STRING, "rank_groups", "z. B. Iron|Bronze|Silver;Silver|Gold; - zum Leeren", false)
                                .addOption(OptionType.INTEGER, "unrestricted_party_size", "Partygröße ohne Ranglimit; 0 zum Deaktivieren", false));

        var review = Commands.slash("review", "Bewertungen moderieren")
                .setDefaultPermissions(ADMIN_ONLY)
                .addSubcommands(
                        new SubcommandData("pending", "Offene private Bewertungstexte anzeigen")
                                .addOption(OptionType.INTEGER, "page", "Seite, beginnend bei 1", false),
                        new SubcommandData("moderate", "Moderationsbewertung mit 1-5 Sternen vergeben")
                                .addOption(OptionType.INTEGER, "assignment_id", "ID der Bewertung", true)
                                .addOption(OptionType.INTEGER, "stars", "Moderationssterne von 1 bis 5", true));

        guild.upsertCommand(game).queue(command -> LOGGER.info("Registered /game on admin guild"),
                error -> LOGGER.error("Could not register /game", error));
        guild.upsertCommand(review).queue(command -> LOGGER.info("Registered /review on admin guild"),
                error -> LOGGER.error("Could not register /review", error));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("game") && !event.getName().equals("review")) return;
        if (!authorized(event)) {
            event.reply(t(event, "Admin.Command.Unauthorized"))
                    .setEphemeral(true).queue();
            return;
        }
        try {
            if (event.getName().equals("game") && "create".equals(event.getSubcommandName())) createGame(event);
            else if (event.getName().equals("game") && "configure".equals(event.getSubcommandName())) configureGame(event);
            else if (event.getName().equals("review") && "pending".equals(event.getSubcommandName())) pendingReviews(event);
            else if (event.getName().equals("review") && "moderate".equals(event.getSubcommandName())) moderateReview(event);
        } catch (IllegalArgumentException exception) {
            event.reply(t(event, "General.InvalidInput", java.util.Map.of("%error%", exception.getMessage()))).setEphemeral(true).queue();
        } catch (RuntimeException exception) {
            LOGGER.error("Admin slash command failed", exception);
            event.reply(t(event, "Admin.Command.InternalError")).setEphemeral(true).queue();
        }
    }

    private static boolean authorized(SlashCommandInteractionEvent event) {
        return event.getGuild() != null && event.getGuild().getIdLong() == ADMIN_GUILD_ID
                && event.getMember() != null && event.getMember().hasPermission(Permission.ADMINISTRATOR);
    }

    private static void createGame(SlashCommandInteractionEvent event) {
        String name = event.getOption("name").getAsString().trim();
        int mainGame = Math.toIntExact(event.getOption("main_game").getAsLong());
        boolean skillbased = event.getOption("skillbased").getAsBoolean();
        String rawStatistics = event.getOption("statistics", "", option -> option.getAsString());
        if (name.isBlank() || name.length() > 255) throw new IllegalArgumentException("Der Game-Name muss 1-255 Zeichen lang sein.");
        if (mainGame < 0) throw new IllegalArgumentException("main_game darf nicht negativ sein.");
        List<GameStatDefinition> statistics = GameStatDefinitionParser.parse(rawStatistics);
        List<String> platforms = names(event, "platforms");
        List<String> regions = names(event, "regions");
        List<String> ranks = names(event, "ranks");
        List<String> roles = names(event, "roles");
        if (mainGame > 0) {
            if (GameRepository.get(mainGame) == null) throw new IllegalArgumentException("Die main_game-ID existiert nicht.");
            if (platforms.isEmpty() && GameOptionRepository.get(mainGame, GameOption.Type.PLATFORM).isEmpty())
                throw new IllegalArgumentException("Ein Modus benötigt Plattformen oder muss sie vom Hauptspiel erben.");
            if (regions.isEmpty() && GameOptionRepository.get(mainGame, GameOption.Type.REGION).isEmpty())
                throw new IllegalArgumentException("Ein Modus benötigt Regionen oder muss sie vom Hauptspiel erben.");
            if (skillbased && ranks.isEmpty() && GameOptionRepository.get(mainGame, GameOption.Type.RANK).isEmpty())
                throw new IllegalArgumentException("Ein skillbased Modus benötigt Rangdefinitionen.");
        }
        GameObject game = GameRepository.create(mainGame, name, skillbased, true, statistics);
        if (game == null) {
            event.reply(t(event, "Admin.Game.CreateFailed")).setEphemeral(true).queue();
            return;
        }
        replaceIfProvided(game.getId(), GameOption.Type.PLATFORM, event, "platforms");
        replaceIfProvided(game.getId(), GameOption.Type.REGION, event, "regions");
        replaceIfProvided(game.getId(), GameOption.Type.RANK, event, "ranks");
        replaceIfProvided(game.getId(), GameOption.Type.ROLE, event, "roles");
        configureRankRulesIfProvided(game.getId(), event);
        event.reply(t(event, "Admin.Game.Created", java.util.Map.of("%game%", game.getName(), "%gameId%",
                String.valueOf(game.getId()), "%statistics%", String.valueOf(statistics.size())))).setEphemeral(true).queue();
    }

    private static void configureGame(SlashCommandInteractionEvent event) {
        int gameId = Math.toIntExact(event.getOption("game_id").getAsLong());
        GameObject game = GameRepository.get(gameId);
        if (game == null) throw new IllegalArgumentException("Game oder Modus nicht gefunden.");
        int changed = 0;
        changed += replaceIfProvided(gameId, GameOption.Type.PLATFORM, event, "platforms");
        changed += replaceIfProvided(gameId, GameOption.Type.REGION, event, "regions");
        changed += replaceIfProvided(gameId, GameOption.Type.RANK, event, "ranks");
        changed += replaceIfProvided(gameId, GameOption.Type.ROLE, event, "roles");
        changed += configureRankRulesIfProvided(gameId, event);
        if (changed == 0) throw new IllegalArgumentException("Gib mindestens eine Konfigurationsliste an.");
        event.reply(t(event, "Admin.Game.Configured", java.util.Map.of("%game%", game.getName()))).setEphemeral(true).queue();
    }

    private static int replaceIfProvided(int gameId, GameOption.Type type, SlashCommandInteractionEvent event, String optionName) {
        if (event.getOption(optionName) == null) return 0;
        if (type == GameOption.Type.RANK) RankCompatibilityRepository.replaceGroups(gameId, "-");
        GameOptionRepository.replace(gameId, type, names(event, optionName));
        return 1;
    }

    private static int configureRankRulesIfProvided(int gameId, SlashCommandInteractionEvent event) {
        int changed = 0;
        if (event.getOption("rank_groups") != null) {
            RankCompatibilityRepository.replaceGroups(gameId, event.getOption("rank_groups").getAsString());
            changed++;
        }
        if (event.getOption("unrestricted_party_size") != null) {
            int size = event.getOption("unrestricted_party_size").getAsInt();
            RankCompatibilityRepository.setUnrestrictedPartySize(gameId, size == 0 ? null : size);
            changed++;
        }
        return changed;
    }

    private static List<String> names(SlashCommandInteractionEvent event, String optionName) {
        String raw = event.getOption(optionName, "", option -> option.getAsString()).trim();
        if (raw.isEmpty() || raw.equals("-")) return List.of();
        return java.util.Arrays.stream(raw.split(",")).map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private static void pendingReviews(SlashCommandInteractionEvent event) {
        List<ReviewRepository.ModerationReview> pending = ReviewRepository.pendingModeration();
        int requestedPage = Math.max(1, event.getOption("page", 1, option -> option.getAsInt()));
        int pageSize = 3;
        int pages = Math.max(1, (pending.size() + pageSize - 1) / pageSize);
        int page = Math.min(requestedPage, pages);
        int from = Math.min((page - 1) * pageSize, pending.size());
        int to = Math.min(from + pageSize, pending.size());
        if (pending.isEmpty()) {
            event.reply(t(event, "Admin.Review.NonePending")).setEphemeral(true).queue();
            return;
        }
        StringBuilder message = new StringBuilder(t(event, "Admin.Review.PendingTitle", java.util.Map.of(
                "%page%", String.valueOf(page), "%pages%", String.valueOf(pages)))).append('\n');
        for (ReviewRepository.ModerationReview review : pending.subList(from, to)) {
            String feedback = review.privateFeedback().replace("```", "''' ");
            if (feedback.length() > 350) feedback = feedback.substring(0, 350) + "…";
            message.append("\nID **").append(review.assignmentId()).append("** · Lobby ").append(review.lobbyId())
                    .append(" · Zieluser ").append(review.targetUserId()).append(" · B/T/Z ")
                    .append(review.behaviorStars()).append('/').append(review.teamplayStars()).append('/')
                    .append(review.reliabilityStars()).append("\n```\n").append(feedback).append("\n```\n");
        }
        event.reply(message.toString()).setEphemeral(true).queue();
    }

    private static void moderateReview(SlashCommandInteractionEvent event) {
        int assignmentId = Math.toIntExact(event.getOption("assignment_id").getAsLong());
        int stars = Math.toIntExact(event.getOption("stars").getAsLong());
        if (stars < 1 || stars > 5) throw new IllegalArgumentException("stars muss zwischen 1 und 5 liegen.");
        boolean updated = ReviewRepository.moderate(assignmentId, stars);
        event.reply(t(event, updated ? "Admin.Review.Moderated" : "Admin.Review.NotFound", java.util.Map.of(
                "%assignmentId%", String.valueOf(assignmentId), "%stars%", String.valueOf(stars)))).setEphemeral(true).queue();
    }

    private static String t(SlashCommandInteractionEvent event, String key) {
        return LanguageManager.getMessageByLanguage(key, language(event));
    }

    private static String t(SlashCommandInteractionEvent event, String key, java.util.Map<String, String> replacements) {
        String message = t(event, key);
        for (java.util.Map.Entry<String, String> replacement : replacements.entrySet())
            message = message.replace(replacement.getKey(), replacement.getValue());
        return message;
    }

    private static Language language(SlashCommandInteractionEvent event) {
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user != null) return user.getLanguage();
        GuildObject guild = event.getGuild() == null ? null : GuildRepository.get(event.getGuild().getIdLong());
        return guild == null ? Language.EN : guild.getLanguage();
    }
}
