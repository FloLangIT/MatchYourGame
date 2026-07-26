package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.game.GameObject;
import de.flolang.matchyourgame.database.game.GameOption;
import de.flolang.matchyourgame.database.game.GameOptionRepository;
import de.flolang.matchyourgame.database.game.GameRepository;
import de.flolang.matchyourgame.database.game.GameStatDefinition;
import de.flolang.matchyourgame.database.game.GameStatRepository;
import de.flolang.matchyourgame.database.game.RankCompatibilityRepository;
import de.flolang.matchyourgame.database.gameapi.GameApiRepository;
import de.flolang.matchyourgame.database.guild.GuildRepository;
import de.flolang.matchyourgame.database.lobby.LobbyObject;
import de.flolang.matchyourgame.database.lobby.LobbyRepository;
import de.flolang.matchyourgame.database.party.PartyObject;
import de.flolang.matchyourgame.database.party.PartyRepository;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.database.user.UserRole;
import de.flolang.matchyourgame.database.user.InboxMessageRepository;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.Language;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.AdminAccess;
import de.flolang.matchyourgame.manager.InboxService;
import de.flolang.matchyourgame.gameapi.GameApiProvider;
import de.flolang.matchyourgame.gameapi.GameApiRegistry;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class AdminPanelListener extends ListenerAdapter {
    private static final int PAGE_SIZE = 23;

    @Override public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.equals("adminPanel") && !id.startsWith("adminActiveLobbies")
                && !id.startsWith("adminActiveParties") && !id.startsWith("adminGames")
                && !id.startsWith("adminGameOpen-") && !id.startsWith("adminGameEdit-")
                && !id.startsWith("adminGameOptions-") && !id.startsWith("adminGameRanks-")
                && !id.startsWith("adminGameRankApi-") && !id.startsWith("adminGameRankApiPage-")
                && !id.equals("adminGameCreate")
                && !id.startsWith("adminGameStats-") && !id.startsWith("adminGameStatAdd-")
                && !id.startsWith("adminGameStatInheritance-")
                && !id.startsWith("adminGameApi-")
                && !id.equals("adminBroadcast")) return;
        UserObject actor = AdminAccess.panelUser(event.getUser().getIdLong());
        if (actor == null) { event.reply("Unauthorized").setEphemeral(true).queue(); return; }
        if (id.equals("adminPanel")) { event.deferEdit().queue(); showPanel(event.getMessage(), actor); }
        else if (id.startsWith("adminActiveLobbies")) {
            event.deferEdit().queue(); showLobbies(event.getMessage(), actor, suffix(id));
        } else if (id.startsWith("adminActiveParties")) {
            event.deferEdit().queue(); showParties(event.getMessage(), actor, suffix(id));
        } else if (id.startsWith("adminGames") || id.startsWith("adminGameOpen-")) {
            if (!AdminAccess.canManageGames(actor)) { event.reply(t(actor, "Admin.Panel.NoPermission")).setEphemeral(true).queue(); return; }
            event.deferEdit().queue();
            if (id.startsWith("adminGameOpen-")) showGame(event.getMessage(), actor, numbers(id)[0], numbers(id)[1]);
            else showGames(event.getMessage(), actor, suffix(id));
        } else if (id.startsWith("adminGameEdit-")) {
            if (!AdminAccess.canManageGames(actor)) { event.reply(t(actor, "Admin.Panel.NoPermission")).setEphemeral(true).queue(); return; }
            int[] values = numbers(id); GameObject game = GameRepository.get(values[0]);
            if (game == null) { event.reply(t(actor, "Admin.Games.NotFound")).setEphemeral(true).queue(); return; }
            event.replyModal(gameModal("adminGameEditSubmit-" + game.getId() + "-" + values[1], actor, game)).queue();
        } else if (id.startsWith("adminGameOptions-")) {
            if (!AdminAccess.canManageGames(actor)) { event.reply(t(actor, "Admin.Panel.NoPermission")).setEphemeral(true).queue(); return; }
            int[] values=numbers(id); GameObject game=GameRepository.get(values[0]);
            if(game==null){event.reply(t(actor,"Admin.Games.NotFound")).setEphemeral(true).queue();return;}
            event.replyModal(optionsModal(game,values[1],actor)).queue();
        } else if (id.startsWith("adminGameRanks-")) {
            if (!AdminAccess.canManageGames(actor)) { event.reply(t(actor, "Admin.Panel.NoPermission")).setEphemeral(true).queue(); return; }
            int[] values = numbers(id); GameObject game = GameRepository.get(values[0]);
            if (game == null || !game.isSkillbased()) {
                event.reply(t(actor, "Admin.Games.NotFound")).setEphemeral(true).queue(); return;
            }
            event.replyModal(rankRulesModal(game, values[1], actor)).queue();
        } else if (id.startsWith("adminGameRankApiPage-")) {
            if (!AdminAccess.canManageGames(actor)) { event.reply(t(actor, "Admin.Panel.NoPermission")).setEphemeral(true).queue(); return; }
            int[] values = trailingNumbers(id, 3);
            event.deferEdit().queue();
            showRankApiMappings(event.getMessage(), actor, values[0], values[1], values[2]);
        } else if (id.startsWith("adminGameRankApi-")) {
            if (!AdminAccess.canManageGames(actor)) { event.reply(t(actor, "Admin.Panel.NoPermission")).setEphemeral(true).queue(); return; }
            int[] values = numbers(id);
            event.deferEdit().queue();
            showRankApiMappings(event.getMessage(), actor, values[0], 0, values[1]);
        } else if (id.startsWith("adminGameStats-")) {
            if (!AdminAccess.canManageGames(actor)) { event.reply(t(actor, "Admin.Panel.NoPermission")).setEphemeral(true).queue(); return; }
            int[] values = numbers(id);
            event.deferEdit().queue();
            showStatistics(event.getMessage(), actor, values[0], values[1]);
        } else if (id.startsWith("adminGameApi-")) {
            if (!AdminAccess.canManageGames(actor)) { event.reply(t(actor, "Admin.Panel.NoPermission")).setEphemeral(true).queue(); return; }
            int[] values = numbers(id);
            GameObject game = GameRepository.get(values[0]);
            if (game == null) { event.reply(t(actor, "Admin.Games.NotFound")).setEphemeral(true).queue(); return; }
            GameApiRepository.DirectApiConfiguration direct = GameApiRepository.directConfiguration(game.getId());
            String current = direct == null
                    ? (game.getSubGameFrom() == null ? "NONE" : "INHERIT")
                    : (direct.enabled() ? direct.providerId() : "NONE");
            List<SelectOption> providers = new ArrayList<>();
            if (game.getSubGameFrom() != null)
                providers.add(SelectOption.of(t(actor, "Admin.Games.API.Inherit"), "INHERIT")
                        .withDefault("INHERIT".equals(current)));
            providers.add(SelectOption.of(t(actor, "Admin.Games.API.None"), "NONE")
                    .withDefault("NONE".equals(current)));
            for (GameApiProvider provider : GameApiRegistry.all())
                providers.add(SelectOption.of(provider.displayName(), provider.id())
                        .withDefault(provider.id().equalsIgnoreCase(current)));
            event.replyModal(Modal.create("adminGameApiSubmit-" + game.getId() + "-" + values[1],
                            t(actor, "Admin.Games.API.Title"))
                    .addComponents(Label.of(t(actor, "Admin.Games.API.Provider"),
                            StringSelectMenu.create("provider").addOptions(providers).setRequiredRange(1, 1).build()))
                    .build()).queue();
        } else if (id.startsWith("adminGameStatAdd-")) {
            if (!AdminAccess.canManageGames(actor)) { event.reply(t(actor, "Admin.Panel.NoPermission")).setEphemeral(true).queue(); return; }
            int[] values = numbers(id);
            GameObject game = GameRepository.get(values[0]);
            if (game == null) { event.reply(t(actor, "Admin.Games.NotFound")).setEphemeral(true).queue(); return; }
            GameStatDefinition.Scope scope = id.contains("-TEAM-") ? GameStatDefinition.Scope.TEAM : GameStatDefinition.Scope.PLAYER;
            event.replyModal(statisticModal(actor, game, values[1], scope, null)).queue();
        } else if (id.startsWith("adminGameStatInheritance-")) {
            if (!AdminAccess.canManageGames(actor)) { event.reply(t(actor, "Admin.Panel.NoPermission")).setEphemeral(true).queue(); return; }
            int[] values = numbers(id);
            GameObject game = GameRepository.get(values[0]);
            if (game == null || game.getSubGameFrom() == null) { event.reply(t(actor, "Admin.Games.NotFound")).setEphemeral(true).queue(); return; }
            boolean saved = GameStatRepository.setInheritsFromParent(game.getId(),
                    !GameStatRepository.inheritsFromParent(game.getId()));
            event.deferEdit().queue();
            if (saved) showStatistics(event.getMessage(), actor, values[0], values[1]);
        } else if (id.equals("adminBroadcast")) {
            if (AdminAccess.role(actor) != UserRole.ADMIN) {
                event.reply(t(actor, "Admin.Panel.NoPermission")).setEphemeral(true).queue(); return;
            }
            event.replyModal(Modal.create("adminBroadcastSubmit", t(actor, "Admin.Broadcast.Modal.Title"))
                    .addComponents(
                            Label.of(t(actor, "Admin.Broadcast.Modal.EmbedTitle"),
                                    TextInput.create("title", TextInputStyle.SHORT).setRequired(true).setMaxLength(100).build()),
                            Label.of(t(actor, "Admin.Broadcast.Modal.Message"),
                                    TextInput.create("message", TextInputStyle.PARAGRAPH).setRequired(true).setMaxLength(3000).build()),
                            languageInput(actor),
                            deliveryInput(actor, "Admin.Broadcast.Modal.Delivery"))
                    .build()).queue();
        } else {
            if (!AdminAccess.canManageGames(actor)) { event.reply(t(actor, "Admin.Panel.NoPermission")).setEphemeral(true).queue(); return; }
            event.replyModal(gameModal("adminGameCreateSubmit", actor, null)).queue();
        }
    }

    @Override public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if ((!event.getComponentId().startsWith("adminGameSelect-")
                && !event.getComponentId().startsWith("adminGameStatSelect-")
                && !event.getComponentId().startsWith("adminGameRankApiSelect-"))
                || event.getValues().isEmpty()) return;
        UserObject actor = AdminAccess.panelUser(event.getUser().getIdLong());
        if (actor == null || !AdminAccess.canManageGames(actor)) { event.reply("Unauthorized").setEphemeral(true).queue(); return; }
        if (event.getComponentId().startsWith("adminGameStatSelect-")) {
            int[] values = numbers(event.getComponentId());
            int statisticId;
            try { statisticId = Integer.parseInt(event.getValues().getFirst()); } catch (NumberFormatException e) { return; }
            GameObject game = GameRepository.get(values[0]);
            GameStatDefinition statistic = GameStatRepository.get(statisticId);
            if (game == null || statistic == null || statistic.gameId() != game.getId()) {
                event.reply(t(actor, "Admin.Games.Statistics.NotFound")).setEphemeral(true).queue(); return;
            }
            event.replyModal(statisticModal(actor, game, values[1], statistic.scope(), statistic)).queue();
            return;
        }
        if (event.getComponentId().startsWith("adminGameRankApiSelect-")) {
            int[] values = trailingNumbers(event.getComponentId(), 3);
            int rankOptionId;
            try { rankOptionId = Integer.parseInt(event.getValues().getFirst()); }
            catch (NumberFormatException exception) { return; }
            GameObject game = GameRepository.get(values[0]);
            GameOption rank = GameOptionRepository.getById(rankOptionId);
            GameApiProvider provider = game == null ? null
                    : GameApiRegistry.get(GameApiRepository.providerId(game.getId()));
            boolean belongsToGame = game != null && rank != null && GameOptionRepository.get(
                    game.getId(), GameOption.Type.RANK).stream().anyMatch(option -> option.id() == rankOptionId);
            if (!belongsToGame || provider == null || provider.rankValues().isEmpty()) {
                event.reply(t(actor, "Admin.Games.RankApi.Unavailable")).setEphemeral(true).queue(); return;
            }
            event.replyModal(rankApiModal(actor, game, rank, provider, values[1], values[2])).queue();
            return;
        }
        int gameId;
        try { gameId = Integer.parseInt(event.getValues().getFirst()); } catch (NumberFormatException e) { return; }
        event.deferEdit().queue(); showGame(event.getMessage(), actor, gameId, suffix(event.getComponentId()));
    }

    @Override public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (!id.equals("adminGameCreateSubmit") && !id.startsWith("adminGameEditSubmit-")
                && !id.startsWith("adminGameOptionsSubmit-")
                && !id.startsWith("adminGameRanksSubmit-")
                && !id.startsWith("adminGameRankApiSubmit-")
                && !id.startsWith("adminGameStatCreateSubmit-")
                && !id.startsWith("adminGameStatEditSubmit-")
                && !id.startsWith("adminGameApiSubmit-")
                && !id.equals("adminBroadcastSubmit")) return;
        UserObject actor = AdminAccess.panelUser(event.getUser().getIdLong());
        if (actor == null) { event.reply("Unauthorized").setEphemeral(true).queue(); return; }
        if (id.equals("adminBroadcastSubmit")) {
            if (AdminAccess.role(actor) != UserRole.ADMIN) {
                event.reply(t(actor, "Admin.Panel.NoPermission")).setEphemeral(true).queue(); return;
            }
            InboxMessageRepository.DeliveryMode delivery = delivery(event);
            Language language = broadcastLanguage(event);
            String title = value(event, "title"), content = value(event, "message");
            event.deferReply(true).queue(hook -> {
                List<InboxMessageRepository.InboxMessage> messages = InboxService.broadcast(
                        actor, title, content, delivery, language);
                hook.editOriginal(t(actor, messages.isEmpty() ? "Admin.Broadcast.Failed" : "Admin.Broadcast.Sent",
                        Map.of("%count%", String.valueOf(messages.size()), "%delivery%",
                                t(actor, "Admin.Delivery." + delivery.name())))).queue();
            });
            return;
        }
        if (!AdminAccess.canManageGames(actor)) { event.reply("Unauthorized").setEphemeral(true).queue(); return; }
        if (id.startsWith("adminGameApiSubmit-")) {
            int[] values = numbers(id);
            String provider = selectValue(event, "provider");
            GameObject game = GameRepository.get(values[0]);
            boolean validInheritance = "INHERIT".equals(provider)
                    && game != null && game.getSubGameFrom() != null;
            boolean saved = game != null && ("NONE".equals(provider) || validInheritance
                    || GameApiRegistry.get(provider) != null)
                    && GameApiRepository.configure(values[0], provider);
            if (saved) Main.gameApiService.autoMapAllRanks();
            event.reply(t(actor, saved ? "Admin.Games.Saved" : "Admin.Games.SaveFailed")).setEphemeral(true).queue();
            if (saved && event.getMessage() != null) showGame(event.getMessage(), actor, values[0], values[1]);
            return;
        }
        if (id.startsWith("adminGameStatCreateSubmit-") || id.startsWith("adminGameStatEditSubmit-")) {
            handleStatisticSubmit(event, actor);
            return;
        }
        if (id.startsWith("adminGameRankApiSubmit-")) {
            int[] values = trailingNumbers(id, 4);
            int rankOptionId = values[0], gameId = values[1], mappingPage = values[2], returnPage = values[3];
            GameObject game = GameRepository.get(gameId);
            GameOption rank = GameOptionRepository.getById(rankOptionId);
            String providerId = GameApiRepository.providerId(gameId);
            GameApiProvider provider = GameApiRegistry.get(providerId);
            boolean validRank = game != null && rank != null && GameOptionRepository.get(
                    gameId, GameOption.Type.RANK).stream().anyMatch(option -> option.id() == rankOptionId);
            boolean saved = validRank && provider != null;
            if (saved && "DELETE".equals(selectValue(event, "rankAction")))
                saved = GameApiRepository.removeRankMapping(rankOptionId);
            else if (saved) {
                String externalRank = selectValue(event, "apiRank");
                boolean validApiRank = provider.rankValues().stream()
                        .anyMatch(value -> value.key().equals(externalRank));
                boolean duplicate = GameOptionRepository.get(gameId, GameOption.Type.RANK).stream()
                        .filter(option -> option.id() != rankOptionId)
                        .map(option -> GameApiRepository.getRankMapping(option.id()))
                        .filter(java.util.Objects::nonNull)
                        .anyMatch(mapping -> provider.id().equalsIgnoreCase(mapping.providerId())
                                && externalRank.equals(mapping.externalRankKey()));
                saved = validApiRank && !duplicate
                        && GameApiRepository.setRankMapping(rankOptionId, provider.id(), externalRank);
            }
            event.reply(t(actor, saved ? "Admin.Games.RankApi.Saved" : "Admin.Games.RankApi.SaveFailed"))
                    .setEphemeral(true).queue();
            if (saved && event.getMessage() != null)
                showRankApiMappings(event.getMessage(), actor, gameId, mappingPage, returnPage);
            return;
        }
        if (id.startsWith("adminGameRanksSubmit-")) {
            int[] values = numbers(id); GameObject game = GameRepository.get(values[0]);
            boolean saved = game != null && game.isSkillbased();
            try {
                if (saved) {
                    String groups = value(event, "rankGroups");
                    String unrestricted = value(event, "unrestrictedPartySize");
                    if ("INHERIT".equalsIgnoreCase(groups)) {
                        if (game.getSubGameFrom() == null) throw new IllegalArgumentException();
                        RankCompatibilityRepository.clearGroupOverride(game.getId());
                    } else RankCompatibilityRepository.replaceGroups(game.getId(), groups);
                    if ("INHERIT".equalsIgnoreCase(unrestricted)) {
                        if (game.getSubGameFrom() == null) throw new IllegalArgumentException();
                        RankCompatibilityRepository.clearUnrestrictedPartySizeOverride(game.getId());
                    } else {
                        int size = Integer.parseInt(unrestricted);
                        RankCompatibilityRepository.setUnrestrictedPartySize(game.getId(), size == 0 ? null : size);
                    }
                }
            } catch (RuntimeException exception) { saved = false; }
            event.reply(t(actor, saved ? "Admin.Games.RankRules.Saved" : "Admin.Games.RankRules.SaveFailed"))
                    .setEphemeral(true).queue();
            if (saved && event.getMessage() != null) showGame(event.getMessage(), actor, values[0], values[1]);
            return;
        }
        if (id.startsWith("adminGameOptionsSubmit-")) {
            int[] values=numbers(id); GameObject game=GameRepository.get(values[0]);
            boolean saved=game!=null;
            try {
                if(saved){
                    GameOptionRepository.replace(game.getId(),GameOption.Type.PLATFORM,csv(value(event,"platforms")));
                    GameOptionRepository.replace(game.getId(),GameOption.Type.REGION,csv(value(event,"regions")));
                    List<String> newRanks = csv(value(event,"ranks"));
                    List<String> oldRanks = GameOptionRepository.get(game.getId(), GameOption.Type.RANK)
                            .stream().map(GameOption::name).toList();
                    if (!oldRanks.equals(newRanks)) {
                        RankCompatibilityRepository.replaceGroups(game.getId(), "-");
                        GameOptionRepository.replace(game.getId(),GameOption.Type.RANK,newRanks);
                    }
                    GameOptionRepository.replace(game.getId(),GameOption.Type.ROLE,csv(value(event,"roles")));
                    Main.gameApiService.autoMapRanks(game.getId());
                }
            } catch (RuntimeException exception) { saved=false; }
            event.reply(t(actor,saved?"Admin.Games.Saved":"Admin.Games.SaveFailed")).setEphemeral(true).queue();
            if(saved&&event.getMessage()!=null)showGame(event.getMessage(),actor,values[0],values[1]);
            return;
        }
        String name = value(event, "name");
        boolean skillbased, active;
        try { skillbased = bool(value(event, "skillbased")); active = bool(value(event, "active")); }
        catch (IllegalArgumentException e) { event.reply(t(actor, "Admin.Games.InvalidBoolean")).setEphemeral(true).queue(); return; }
        boolean saved;
        int page = 0;
        if (id.equals("adminGameCreateSubmit")) {
            int parent;
            try { parent = Integer.parseInt(value(event, "parent")); } catch (NumberFormatException e) { parent = -1; }
            saved = parent >= 0 && (parent == 0 || GameRepository.get(parent) != null)
                    && GameRepository.create(parent, name, skillbased, active) != null;
        } else {
            int[] values = numbers(id); page = values[1];
            saved = GameRepository.updateBasic(values[0], name, skillbased, active);
        }
        event.reply(t(actor, saved ? "Admin.Games.Saved" : "Admin.Games.SaveFailed")).setEphemeral(true).queue();
        if (saved && event.getMessage() != null) showGames(event.getMessage(), actor, page);
    }

    private static void showPanel(Message message, UserObject actor) {
        List<LobbyObject> lobbies = LobbyRepository.getAllActive();
        List<PartyObject> parties = PartyRepository.getAllActive();
        long partners = GuildRepository.getAll().stream().filter(g -> g.isPartnerGuild()).count();
        String description = t(actor, "Admin.Panel.Description", Map.of(
                "%role%", AdminAccess.role(actor).name(), "%uptime%", "<t:" + Main.STARTED_AT.getEpochSecond() + ":R>",
                "%users%", String.valueOf(UserRepository.activeCount()), "%guilds%", String.valueOf(GuildRepository.getAll().size()),
                "%partners%", String.valueOf(partners), "%lobbies%", String.valueOf(lobbies.size()),
                "%parties%", String.valueOf(parties.size()), "%games%", String.valueOf(GameRepository.getAllGames().size())));
        List<ActionRow> rows = new ArrayList<>();
        List<Button> management = new ArrayList<>();
        if (AdminAccess.canManageUsers(actor)) management.add(Button.primary("adminUsers", t(actor, "Admin.Panel.Users")));
        if (AdminAccess.canViewGuilds(actor)) management.add(Button.primary("adminGuilds", t(actor, "Admin.Panel.Guilds")));
        if (AdminAccess.canInviteUsers(actor)) management.add(Button.success("projectInvite", t(actor, "Admin.Panel.Invite")));
        if (!management.isEmpty()) rows.add(ActionRow.of(management));
        rows.add(ActionRow.of(Button.secondary("adminActiveLobbies-0", t(actor, "Admin.Panel.Lobbies")),
                Button.secondary("adminActiveParties-0", t(actor, "Admin.Panel.Parties"))));
        if (AdminAccess.canManageGames(actor)) rows.add(ActionRow.of(Button.primary("adminGames-0", t(actor, "Admin.Panel.Games"))));
        if (AdminAccess.role(actor) == UserRole.ADMIN)
            rows.add(ActionRow.of(Button.success("adminBroadcast", t(actor, "Admin.Panel.Broadcast"))));
        rows.add(ActionRow.of(Button.primary("mainPage", t(actor, "UserProfile.Button.Back"))));
        message.editMessageEmbeds(new EmbedCreator().setTitle(t(actor, "Admin.Panel.Title")).setDescription(description).build())
                .setComponents(rows).queue();
    }

    private static void showLobbies(Message message, UserObject actor, int requested) {
        List<LobbyObject> all = LobbyRepository.getAllActive();
        showPaged(message, actor, requested, all.stream().map(lobby -> {
            GameObject game = GameRepository.get(lobby.getGameID()); UserObject host = UserRepository.get(lobby.getLeaderID());
            return t(actor,"Admin.ActiveLobbies.Entry",Map.of("%id%",String.valueOf(lobby.getId()),
                    "%game%",game == null ? String.valueOf(lobby.getGameID()) : game.getName(),
                    "%players%",String.valueOf(LobbyRepository.memberCount(lobby.getId())),
                    "%capacity%",String.valueOf(lobby.getMaxPlayers()),"%status%",lobby.getStatus().name(),
                    "%host%",host == null ? "-" : host.getUsername()));
        }).toList(), "Admin.ActiveLobbies", "adminActiveLobbies-");
    }

    private static void showParties(Message message, UserObject actor, int requested) {
        List<PartyObject> all = PartyRepository.getAllActive();
        showPaged(message, actor, requested, all.stream().map(party -> {
            UserObject host = UserRepository.get(party.hostUserId());
            String members = party.memberIds().stream().map(UserRepository::get).filter(java.util.Objects::nonNull)
                    .map(UserObject::getUsername).reduce((a,b) -> a + ", " + b).orElse("-");
            return t(actor,"Admin.ActiveParties.Entry",Map.of("%id%",String.valueOf(party.id()),
                    "%host%",host == null ? "-" : host.getUsername(),"%count%",String.valueOf(party.memberIds().size()),
                    "%members%",members));
        }).toList(), "Admin.ActiveParties", "adminActiveParties-");
    }

    private static void showPaged(Message message, UserObject actor, int requested, List<String> entries,
                                  String key, String buttonPrefix) {
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE), page = Math.max(0, Math.min(requested, pages - 1));
        int from = Math.min(page * PAGE_SIZE, entries.size()), to = Math.min(from + PAGE_SIZE, entries.size());
        String content = entries.subList(from, to).stream().reduce((a,b) -> a + "\n\n" + b).orElse(t(actor, key + ".None"));
        List<ActionRow> rows = new ArrayList<>();
        if (pages > 1) rows.add(ActionRow.of(Button.secondary(buttonPrefix + Math.max(0,page-1), t(actor,"General.Previous")).withDisabled(page==0),
                Button.secondary(buttonPrefix + Math.min(pages-1,page+1), t(actor,"General.Next")).withDisabled(page>=pages-1)));
        rows.add(ActionRow.of(Button.primary("adminPanel", t(actor,"UserProfile.Button.Back"))));
        message.editMessageEmbeds(new EmbedCreator().setTitle(t(actor,key+".Title"))
                .setDescription(t(actor,key+".Description", Map.of("%count%",String.valueOf(entries.size()),"%page%",String.valueOf(page+1),
                        "%pages%",String.valueOf(pages),"%entries%",content))).build()).setComponents(rows).queue();
    }

    private static void showGames(Message message, UserObject actor, int requested) {
        List<GameObject> games = GameRepository.getAllGames().stream().sorted(Comparator.comparing(GameObject::getName)).toList();
        Map<Integer, GameRepository.GameActivityStats> activity = GameRepository.activityStats();
        int pages=Math.max(1,(games.size()+PAGE_SIZE-1)/PAGE_SIZE), page=Math.max(0,Math.min(requested,pages-1));
        int from=Math.min(page*PAGE_SIZE,games.size()),to=Math.min(from+PAGE_SIZE,games.size()); List<GameObject> shown=games.subList(from,to);
        String entries=shown.stream().map(g -> {
                    GameRepository.GameActivityStats stats = activity.getOrDefault(g.getId(),
                            new GameRepository.GameActivityStats(0, 0));
                    return t(actor,"Admin.Games.Entry",Map.of("%id%",String.valueOf(g.getId()),
                        "%name%",g.getName(),"%active%",g.isActive()?t(actor,"General.On"):t(actor,"General.Off"),
                        "%skillbased%",g.isSkillbased()?t(actor,"General.On"):t(actor,"General.Off"),
                        "%parent%",g.getSubGameFrom()==null?"-":String.valueOf(g.getSubGameFrom().getId()),
                        "%passiveUsers%",String.valueOf(stats.passiveUsers()),
                        "%openLobbies%",String.valueOf(stats.openLobbies())));
                })
                .reduce((a,b)->a+"\n"+b).orElse(t(actor,"Admin.Games.None"));
        List<ActionRow> rows=new ArrayList<>();
        if(!shown.isEmpty()) rows.add(ActionRow.of(StringSelectMenu.create("adminGameSelect-"+page).setPlaceholder(t(actor,"Admin.Games.Select"))
                .addOptions(shown.stream().map(g->SelectOption.of(g.getName(),String.valueOf(g.getId()))).toList()).build()));
        if(pages>1) rows.add(ActionRow.of(Button.secondary("adminGames-"+Math.max(0,page-1),t(actor,"General.Previous")).withDisabled(page==0),
                Button.secondary("adminGames-"+Math.min(pages-1,page+1),t(actor,"General.Next")).withDisabled(page>=pages-1)));
        rows.add(ActionRow.of(Button.success("adminGameCreate",t(actor,"Admin.Games.Create")),Button.primary("adminPanel",t(actor,"UserProfile.Button.Back"))));
        message.editMessageEmbeds(new EmbedCreator().setTitle(t(actor,"Admin.Games.Title")).setDescription(t(actor,"Admin.Games.Description",Map.of(
                "%count%",String.valueOf(games.size()),"%page%",String.valueOf(page+1),"%pages%",String.valueOf(pages),"%games%",entries))).build())
                .setComponents(rows).queue();
    }

    private static void showGame(Message message, UserObject actor, int gameId, int page) {
        GameObject game=GameRepository.get(gameId); if(game==null){showGames(message,actor,page);return;}
        String parent=game.getSubGameFrom()==null?"-":"#"+game.getSubGameFrom().getId()+" · "+game.getSubGameFrom().getName();
        GameRepository.GameActivityStats stats = GameRepository.activityStats(gameId);
        GameApiRepository.EffectiveApiConfiguration api = GameApiRepository.effectiveConfiguration(gameId);
        String provider = api.providerId() == null ? t(actor, "Admin.Games.API.None") : api.providerId();
        if (api.inherited()) provider = t(actor, "Admin.Games.API.InheritedFrom", Map.of(
                "%provider%", provider, "%gameId%", String.valueOf(api.sourceGameId())));
        List<ActionRow> rows = new ArrayList<>();
        rows.add(ActionRow.of(
                Button.primary("adminGameEdit-"+gameId+"-"+page,t(actor,"Admin.Games.Edit")),
                Button.secondary("adminGameOptions-"+gameId+"-"+page,t(actor,"Admin.Games.Options")),
                Button.secondary("adminGameStats-"+gameId+"-"+page,t(actor,"Admin.Games.Statistics.Button")),
                Button.secondary("adminGameApi-"+gameId+"-"+page,t(actor,"Admin.Games.API.Button")),
                Button.primary("adminGames-"+page,t(actor,"UserProfile.Button.Back"))));
        if (game.isSkillbased()) {
            List<Button> rankButtons = new ArrayList<>();
            rankButtons.add(Button.secondary(
                    "adminGameRanks-" + gameId + "-" + page, t(actor, "Admin.Games.RankRules.Button")));
            GameApiProvider apiProvider = GameApiRegistry.get(api.providerId());
            if (apiProvider != null && !apiProvider.rankValues().isEmpty())
                rankButtons.add(Button.secondary("adminGameRankApi-" + gameId + "-" + page,
                        t(actor, "Admin.Games.RankApi.Button")));
            rows.add(ActionRow.of(rankButtons));
        }
        message.editMessageEmbeds(new EmbedCreator().setTitle(game.getName()).setDescription(t(actor,"Admin.Games.Detail",Map.of(
                        "%id%",String.valueOf(game.getId()),"%parent%",parent,"%skillbased%",String.valueOf(game.isSkillbased()),
                        "%active%",String.valueOf(game.isActive()),
                        "%passiveUsers%",String.valueOf(stats.passiveUsers()),
                        "%openLobbies%",String.valueOf(stats.openLobbies()),
                        "%api%", provider))).build()).setComponents(rows).queue();
    }

    private static void showStatistics(Message message, UserObject actor, int gameId, int page) {
        GameObject game = GameRepository.get(gameId);
        if (game == null) { showGames(message, actor, page); return; }
        List<GameStatDefinition> own = GameStatRepository.getForGame(gameId);
        boolean mode = game.getSubGameFrom() != null;
        boolean inherits = mode && GameStatRepository.inheritsFromParent(gameId);
        List<GameStatDefinition> inherited = inherits
                ? GameStatRepository.getEffectiveForGame(game.getSubGameFrom().getId()) : List.of();
        String inheritedText = inherited.stream().map(AdminPanelListener::statisticLine)
                .reduce((a, b) -> a + "\n" + b).orElse(t(actor, "Admin.Games.Statistics.None"));
        String ownText = own.stream().map(AdminPanelListener::statisticLine)
                .reduce((a, b) -> a + "\n" + b).orElse(t(actor, "Admin.Games.Statistics.None"));
        String description = t(actor, "Admin.Games.Statistics.Description", Map.of(
                "%inheritance%", mode ? (inherits ? t(actor, "General.On") : t(actor, "General.Off")) : "-",
                "%inherited%", inheritedText, "%own%", ownText));
        List<ActionRow> rows = new ArrayList<>();
        rows.add(ActionRow.of(
                Button.success("adminGameStatAdd-TEAM-" + gameId + "-" + page,
                        t(actor, "Admin.Games.Statistics.AddTeam")),
                Button.success("adminGameStatAdd-PLAYER-" + gameId + "-" + page,
                        t(actor, "Admin.Games.Statistics.AddPlayer"))));
        if (mode) rows.add(ActionRow.of(Button.secondary("adminGameStatInheritance-" + gameId + "-" + page,
                t(actor, inherits ? "Admin.Games.Statistics.DisableInheritance" : "Admin.Games.Statistics.EnableInheritance"))));
        if (!own.isEmpty()) rows.add(ActionRow.of(StringSelectMenu.create("adminGameStatSelect-" + gameId + "-" + page)
                .setPlaceholder(t(actor, "Admin.Games.Statistics.Select"))
                .addOptions(own.stream().limit(25).map(stat -> SelectOption.of(
                        stat.name().length() > 80 ? stat.name().substring(0, 80) : stat.name(), String.valueOf(stat.id()))
                        .withDescription(stat.scope().name() + " · " + stat.valueType().name())).toList()).build()));
        rows.add(ActionRow.of(Button.primary("adminGameOpen-" + gameId + "-" + page,
                t(actor, "UserProfile.Button.Back"))));
        message.editMessageEmbeds(new EmbedCreator().setTitle(t(actor, "Admin.Games.Statistics.Title",
                Map.of("%game%", game.getName()))).setDescription(description).build()).setComponents(rows).queue();
    }

    private static void showRankApiMappings(Message message, UserObject actor, int gameId,
                                            int requestedPage, int returnPage) {
        GameObject game = GameRepository.get(gameId);
        GameApiProvider provider = game == null ? null
                : GameApiRegistry.get(GameApiRepository.providerId(gameId));
        if (game == null || provider == null || provider.rankValues().isEmpty()) {
            showGame(message, actor, gameId, returnPage); return;
        }
        List<GameOption> ranks = GameOptionRepository.get(gameId, GameOption.Type.RANK);
        int pages = Math.max(1, (ranks.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int from = Math.min(page * PAGE_SIZE, ranks.size());
        int to = Math.min(from + PAGE_SIZE, ranks.size());
        List<GameOption> shown = ranks.subList(from, to);
        String entries = shown.stream().map(rank -> {
            GameApiRepository.RankMapping mapping = GameApiRepository.getRankMapping(rank.id());
            String target = mapping == null || !provider.id().equalsIgnoreCase(mapping.providerId())
                    ? t(actor, "Admin.Games.RankApi.NotMapped")
                    : provider.rankValues().stream().filter(value -> value.key().equals(mapping.externalRankKey()))
                    .map(GameApiProvider.RankValue::label).findFirst().orElse(mapping.externalRankKey());
            return "• **" + rank.name() + "** → " + target;
        }).reduce((first, next) -> first + "\n" + next).orElse(t(actor, "Admin.Games.RankApi.NoRanks"));
        List<ActionRow> rows = new ArrayList<>();
        if (!shown.isEmpty()) rows.add(ActionRow.of(StringSelectMenu.create(
                        "adminGameRankApiSelect-" + gameId + "-" + page + "-" + returnPage)
                .setPlaceholder(t(actor, "Admin.Games.RankApi.Select"))
                .addOptions(shown.stream().map(rank -> SelectOption.of(rank.name(), String.valueOf(rank.id())))
                        .toList()).build()));
        if (pages > 1) rows.add(ActionRow.of(
                Button.secondary("adminGameRankApiPage-" + gameId + "-" + Math.max(0, page - 1) + "-" + returnPage,
                        t(actor, "General.Previous")).withDisabled(page == 0),
                Button.secondary("adminGameRankApiPage-" + gameId + "-" + Math.min(pages - 1, page + 1) + "-" + returnPage,
                        t(actor, "General.Next")).withDisabled(page >= pages - 1)));
        rows.add(ActionRow.of(Button.primary("adminGameOpen-" + gameId + "-" + returnPage,
                t(actor, "UserProfile.Button.Back"))));
        message.editMessageEmbeds(new EmbedCreator().setTitle(t(actor, "Admin.Games.RankApi.Title",
                        Map.of("%game%", game.getName())))
                .setDescription(t(actor, "Admin.Games.RankApi.Description", Map.of(
                        "%provider%", provider.displayName(), "%page%", String.valueOf(page + 1),
                        "%pages%", String.valueOf(pages), "%mappings%", entries))).build())
                .setComponents(rows).queue();
    }

    private static String statisticLine(GameStatDefinition statistic) {
        GameApiRepository.StatisticMapping mapping = GameApiRepository.getStatisticMapping(statistic.id());
        String api = mapping == null ? "" : " · API: `" + mapping.fieldKey() + "`";
        return "• **" + statistic.name() + "** · " + statistic.scope().name() + " · " + statistic.valueType().name() + api;
    }

    private static Modal optionsModal(GameObject game,int page,UserObject actor){
        return Modal.create("adminGameOptionsSubmit-"+game.getId()+"-"+page,t(actor,"Admin.Games.OptionsTitle"))
                .addComponents(optionInput(actor,"Admin.Games.Platforms","platforms",game,GameOption.Type.PLATFORM),
                        optionInput(actor,"Admin.Games.Regions","regions",game,GameOption.Type.REGION),
                        optionInput(actor,"Admin.Games.Ranks","ranks",game,GameOption.Type.RANK),
                        optionInput(actor,"Admin.Games.Roles","roles",game,GameOption.Type.ROLE)).build();
    }

    private static Modal rankRulesModal(GameObject game, int page, UserObject actor) {
        boolean mode = game.getSubGameFrom() != null;
        String groups = mode && RankCompatibilityRepository.inheritsGroups(game.getId())
                ? "INHERIT" : RankCompatibilityRepository.getGroupSpecification(game.getId());
        Integer unrestrictedSize = RankCompatibilityRepository.getUnrestrictedPartySize(game.getId());
        String unrestricted = mode && RankCompatibilityRepository.inheritsUnrestrictedPartySize(game.getId())
                ? "INHERIT" : String.valueOf(unrestrictedSize == null ? 0 : unrestrictedSize);
        return Modal.create("adminGameRanksSubmit-" + game.getId() + "-" + page,
                        t(actor, "Admin.Games.RankRules.Title"))
                .addComponents(
                        Label.of(t(actor, "Admin.Games.RankRules.Groups"),
                                t(actor, "Admin.Games.RankRules.GroupsDescription"),
                                TextInput.create("rankGroups", TextInputStyle.PARAGRAPH).setRequired(true)
                                        .setMaxLength(4000).setValue(groups).build()),
                        Label.of(t(actor, "Admin.Games.RankRules.UnrestrictedPartySize"),
                                t(actor, "Admin.Games.RankRules.UnrestrictedPartySizeDescription"),
                                TextInput.create("unrestrictedPartySize", TextInputStyle.SHORT).setRequired(true)
                                        .setMaxLength(10).setValue(unrestricted).build()))
                .build();
    }

    private static Modal rankApiModal(UserObject actor, GameObject game, GameOption rank,
                                      GameApiProvider provider, int mappingPage, int returnPage) {
        GameApiRepository.RankMapping current = GameApiRepository.getRankMapping(rank.id());
        List<SelectOption> apiRanks = provider.rankValues().stream().limit(25)
                .map(value -> SelectOption.of(value.label(), value.key()).withDefault(
                        current != null && provider.id().equalsIgnoreCase(current.providerId())
                                && value.key().equals(current.externalRankKey()))).toList();
        return Modal.create("adminGameRankApiSubmit-" + rank.id() + "-" + game.getId()
                                + "-" + mappingPage + "-" + returnPage,
                        t(actor, "Admin.Games.RankApi.EditTitle"))
                .addComponents(
                        Label.of(t(actor, "Admin.Games.RankApi.ApiRank"),
                                StringSelectMenu.create("apiRank").addOptions(apiRanks)
                                        .setRequiredRange(1, 1).build()),
                        Label.of(t(actor, "Admin.Games.RankApi.Action"),
                                StringSelectMenu.create("rankAction")
                                        .addOption(t(actor, "Admin.Games.RankApi.Save"), "SAVE")
                                        .addOption(t(actor, "Admin.Games.RankApi.Delete"), "DELETE")
                                        .setRequiredRange(1, 1).build()))
                .build();
    }

    private static Label optionInput(UserObject actor,String key,String id,GameObject game,GameOption.Type type){
        String current=GameOptionRepository.get(game.getId(),type).stream().map(GameOption::name).reduce((a,b)->a+", "+b).orElse("");
        TextInput.Builder input = TextInput.create(id,TextInputStyle.PARAGRAPH).setRequired(false).setMaxLength(2000);
        if (!current.isBlank()) input.setValue(current);
        return Label.of(t(actor,key), input.build());
    }

    private static Modal gameModal(String id, UserObject actor, GameObject game) {
        TextInput.Builder nameInput = TextInput.create("name",TextInputStyle.SHORT).setRequired(true).setMaxLength(255);
        if (game != null && !game.getName().isBlank()) nameInput.setValue(game.getName());
        Modal.Builder modal=Modal.create(id,t(actor,game==null?"Admin.Games.CreateTitle":"Admin.Games.EditTitle"))
                .addComponents(Label.of(t(actor,"Admin.Games.Name"), nameInput.build()),
                        Label.of(t(actor,"Admin.Games.Skillbased"),TextInput.create("skillbased",TextInputStyle.SHORT).setRequired(true)
                                .setValue(String.valueOf(game!=null&&game.isSkillbased())).build()),
                        Label.of(t(actor,"Admin.Games.Active"),TextInput.create("active",TextInputStyle.SHORT).setRequired(true)
                                .setValue(String.valueOf(game==null||game.isActive())).build()));
        if(game==null) modal.addComponents(Label.of(t(actor,"Admin.Games.Parent"),TextInput.create("parent",TextInputStyle.SHORT)
                .setRequired(true).setValue("0").build()));
        return modal.build();
    }

    private static Modal statisticModal(UserObject actor, GameObject game, int page,
                                        GameStatDefinition.Scope scope, GameStatDefinition statistic) {
        boolean editing = statistic != null;
        String id = editing ? "adminGameStatEditSubmit-" + statistic.id() + "-" + game.getId() + "-" + page
                : "adminGameStatCreateSubmit-" + scope.name() + "-" + game.getId() + "-" + page;
        TextInput.Builder name = TextInput.create("statName", TextInputStyle.SHORT).setRequired(true).setMaxLength(80);
        if (editing) name.setValue(statistic.name());
        List<SelectOption> types = new ArrayList<>();
        for (GameStatDefinition.ValueType type : GameStatDefinition.ValueType.values())
            types.add(SelectOption.of(t(actor, "Admin.Games.Statistics.Type." + type.name()), type.name())
                    .withDefault(editing && statistic.valueType() == type));
        Modal.Builder modal = Modal.create(id, t(actor, editing
                        ? "Admin.Games.Statistics.EditTitle" : "Admin.Games.Statistics.AddTitle"))
                .addComponents(Label.of(t(actor, "Admin.Games.Statistics.Name"), name.build()),
                        Label.of(t(actor, "Admin.Games.Statistics.ValueType"), StringSelectMenu.create("statType")
                                .addOptions(types).setRequiredRange(1, 1).build()));
        if (editing) modal.addComponents(Label.of(t(actor, "Admin.Games.Statistics.Action"),
                StringSelectMenu.create("statAction")
                        .addOption(t(actor, "Admin.Games.Statistics.Save"), "SAVE")
                        .addOption(t(actor, "Admin.Games.Statistics.Delete"), "DELETE")
                        .setRequiredRange(1, 1).build()));
        String providerId = GameApiRepository.providerId(game.getId());
        GameApiProvider provider = GameApiRegistry.get(providerId);
        if (provider != null) {
            GameApiRepository.StatisticMapping current = editing
                    ? GameApiRepository.getStatisticMapping(statistic.id()) : null;
            List<SelectOption> fields = new ArrayList<>();
            fields.add(SelectOption.of(t(actor, "Admin.Games.Statistics.ApiNone"), "NONE")
                    .withDefault(current == null));
            provider.statisticFields().stream().filter(field ->
                            scope == GameStatDefinition.Scope.PLAYER
                                    ? field.scope() == GameApiProvider.StatisticField.Scope.PLAYER
                                    : field.scope() != GameApiProvider.StatisticField.Scope.PLAYER)
                    .filter(field -> statisticTypeCompatible(
                            editing ? statistic.valueType() : null, field.valueType()))
                    .limit(24).forEach(field -> fields.add(SelectOption.of(field.label(), field.key())
                            .withDefault(current != null && field.key().equals(current.fieldKey()))));
            modal.addComponents(Label.of(t(actor, "Admin.Games.Statistics.ApiField"),
                    StringSelectMenu.create("apiField").addOptions(fields).setRequiredRange(1, 1).build()));
        }
        return modal.build();
    }

    private static boolean statisticTypeCompatible(GameStatDefinition.ValueType statisticType,
                                                   GameApiProvider.StatisticField.ValueType apiType) {
        return statisticType == null || statisticType == GameStatDefinition.ValueType.TEXT
                || statisticType.name().equals(apiType.name());
    }

    private static void handleStatisticSubmit(ModalInteractionEvent event, UserObject actor) {
        String[] parts = event.getModalId().split("-");
        boolean editing = event.getModalId().startsWith("adminGameStatEditSubmit-");
        int statisticId = 0, gameId, page;
        try {
            if (editing) {
                statisticId = Integer.parseInt(parts[parts.length - 3]);
                gameId = Integer.parseInt(parts[parts.length - 2]);
            } else gameId = Integer.parseInt(parts[parts.length - 2]);
            page = Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException exception) {
            event.reply(t(actor, "Admin.Games.Statistics.SaveFailed")).setEphemeral(true).queue(); return;
        }
        GameObject game = GameRepository.get(gameId);
        if (game == null) { event.reply(t(actor, "Admin.Games.NotFound")).setEphemeral(true).queue(); return; }
        boolean saved;
        GameStatDefinition savedDefinition = null;
        try {
            if (editing && "DELETE".equals(selectValue(event, "statAction"))) {
                saved = GameStatRepository.remove(statisticId, gameId);
            } else {
                GameStatDefinition.ValueType type = GameStatDefinition.ValueType.valueOf(selectValue(event, "statType"));
                String name = value(event, "statName");
                if (editing) {
                    saved = GameStatRepository.update(statisticId, gameId, name, type);
                    savedDefinition = GameStatRepository.get(statisticId);
                }
                else {
                    GameStatDefinition.Scope scope = GameStatDefinition.Scope.valueOf(parts[parts.length - 3]);
                    savedDefinition = GameStatRepository.create(gameId, name, scope, type, true);
                    saved = savedDefinition != null;
                }
                String providerId = GameApiRepository.providerId(gameId);
                if (saved && providerId != null && event.getValue("apiField") != null)
                    saved = GameApiRepository.setStatisticMapping(savedDefinition.id(), providerId,
                            selectValue(event, "apiField"));
            }
        } catch (RuntimeException exception) { saved = false; }
        event.reply(t(actor, saved ? "Admin.Games.Statistics.Saved" : "Admin.Games.Statistics.SaveFailed"))
                .setEphemeral(true).queue();
        if (saved && event.getMessage() != null) showStatistics(event.getMessage(), actor, gameId, page);
    }

    private static String selectValue(ModalInteractionEvent event, String id) {
        return event.getValue(id).getAsStringList().getFirst();
    }

    private static Label deliveryInput(UserObject actor, String key) {
        return Label.of(t(actor, key), StringSelectMenu.create("delivery")
                .addOption(t(actor, "Admin.Delivery.SILENT"), InboxMessageRepository.DeliveryMode.SILENT.name())
                .addOption(t(actor, "Admin.Delivery.DIRECT_DM"), InboxMessageRepository.DeliveryMode.DIRECT_DM.name())
                .setRequiredRange(1, 1).build());
    }

    private static Label languageInput(UserObject actor) {
        StringSelectMenu.Builder menu = StringSelectMenu.create("language").setRequiredRange(1, 1);
        for (Language language : Language.values())
            menu.addOption(t(actor, "Admin.Broadcast.Language." + language.name()), language.name());
        return Label.of(t(actor, "Admin.Broadcast.Modal.Language"), menu.build());
    }

    private static Language broadcastLanguage(ModalInteractionEvent event) {
        try { return Language.valueOf(selectValue(event, "language")); }
        catch (Exception e) { return Language.EN; }
    }

    private static InboxMessageRepository.DeliveryMode delivery(ModalInteractionEvent event) {
        try { return InboxMessageRepository.DeliveryMode.valueOf(
                event.getValue("delivery").getAsStringList().getFirst()); }
        catch (Exception e) { return InboxMessageRepository.DeliveryMode.SILENT; }
    }

    private static boolean bool(String value){if(value.equalsIgnoreCase("true")||value.equalsIgnoreCase("on")||value.equals("1")||value.equalsIgnoreCase("ja"))return true;
        if(value.equalsIgnoreCase("false")||value.equalsIgnoreCase("off")||value.equals("0")||value.equalsIgnoreCase("nein"))return false;throw new IllegalArgumentException();}
    private static int suffix(String id){try{return Integer.parseInt(id.substring(id.lastIndexOf('-')+1));}catch(Exception e){return 0;}}
    private static int[] numbers(String id){String[] p=id.split("-");int[] n={0,0};try{n[0]=Integer.parseInt(p[p.length-2]);n[1]=Integer.parseInt(p[p.length-1]);}catch(Exception ignored){}return n;}
    private static int[] trailingNumbers(String id, int count) {
        String[] parts = id.split("-");
        int[] values = new int[count];
        try {
            for (int i = 0; i < count; i++)
                values[i] = Integer.parseInt(parts[parts.length - count + i]);
        } catch (NumberFormatException ignored) {}
        return values;
    }
    private static String value(ModalInteractionEvent e,String id){return e.getValue(id).getAsString().trim();}
    private static List<String> csv(String value){return value.isBlank()?List.of():java.util.Arrays.stream(value.split(",")).map(String::trim).filter(v->!v.isBlank()).toList();}
    private static String t(UserObject u,String key){return LanguageManager.getMessageForUser(key,u.getId());}
    private static String t(UserObject u,String key,Map<String,String> values){return LanguageManager.getMessageForUser(key,u.getId(),values);}
}
