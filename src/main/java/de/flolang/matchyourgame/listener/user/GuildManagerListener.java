package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.config.ConfigManager;
import de.flolang.matchyourgame.database.guild.GuildObject;
import de.flolang.matchyourgame.database.guild.GuildRepository;
import de.flolang.matchyourgame.database.guild.GuildSetupAuthorizationRepository;
import de.flolang.matchyourgame.database.guild.PartnerGuildApplicationRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.Language;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.logging.DiscordLogService;
import de.flolang.matchyourgame.manager.ManagementMessageUpdater;
import de.flolang.matchyourgame.manager.PartnerGuildService;
import de.flolang.matchyourgame.manager.UserControlManager;
import de.flolang.matchyourgame.manager.review.RatingFormatter;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GuildManagerListener extends ListenerAdapter {
    private static final int PAGE_SIZE = 23;

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id.startsWith("transferGuildManage-")) {
            openTransferModal(event, suffixLong(id), true, 0);
            return;
        }
        if (!id.equals("guildManager") && !id.startsWith("guildManager")) return;
        UserObject actor = UserController.get(event.getUser().getIdLong());
        if (actor == null) { event.reply("Unauthorized").setEphemeral(true).queue(); return; }
        if (id.equals("guildManager") || id.startsWith("guildManagerPage-")) {
            int page = id.equals("guildManager") ? 0 : suffixInt(id);
            event.deferEdit().queue();
            showEntry(event.getMessage(), actor, page);
        } else if (id.startsWith("guildManagerOpen-")) {
            Parsed parsed = parse(id);
            if (parsed == null || !manages(actor, parsed.guildId())) {
                event.reply(t(actor, "GuildManager.NotFound")).setEphemeral(true).queue(); return;
            }
            event.deferEdit().queue();
            showGuild(event.getMessage(), actor, parsed.guildId(), parsed.page());
        } else if (id.startsWith("guildManagerTransfer-")) {
            Parsed parsed = parse(id);
            if (parsed == null || !manages(actor, parsed.guildId())) {
                event.reply(t(actor, "GuildManager.Transfer.NotAuthorized")).setEphemeral(true).queue(); return;
            }
            openTransferModal(event, parsed.guildId(), false, parsed.page());
        } else if (id.startsWith("guildManagerApply-")) {
            Parsed parsed = parse(id);
            if (parsed == null || !canApply(actor, parsed.guildId())) {
                event.reply(t(actor, "PartnerProgram.Invalid")).setEphemeral(true).queue(); return;
            }
            event.replyModal(Modal.create("guildManagerApplySubmit-" + parsed.guildId() + "-" + parsed.page(),
                            t(actor, "PartnerProgram.Application.Title"))
                    .addComponents(Label.of(t(actor, "PartnerProgram.Application.Note"),
                            TextInput.create("note", TextInputStyle.PARAGRAPH).setRequired(false)
                                    .setMaxLength(2000).build())).build()).queue();
        } else if (id.startsWith("guildManagerWithdraw-")) {
            Parsed parsed = parse(id);
            if (parsed == null || !manages(actor, parsed.guildId())) {
                event.reply(t(actor, "GuildManager.Transfer.NotAuthorized")).setEphemeral(true).queue(); return;
            }
            boolean success = GuildRepository.withdrawPartnerGuild(parsed.guildId(), actor.getId());
            event.reply(t(actor, success ? "GuildManager.Partner.Withdrawn" : "GuildManager.Partner.WithdrawFailed"))
                    .setEphemeral(true).queue();
            if (success) {
                DiscordLogService.action("PARTNER_GUILD_WITHDRAWN", actor.getUsername() + " (#" + actor.getId()
                        + ") hat den Partnerstatus der Guild " + parsed.guildId() + " zurückgezogen");
                showGuild(event.getMessage(), actor, parsed.guildId(), parsed.page());
            }
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().startsWith("guildManagerSelect-") || event.getValues().isEmpty()) return;
        UserObject actor = UserController.get(event.getUser().getIdLong());
        long guildId;
        try { guildId = Long.parseLong(event.getValues().getFirst()); }
        catch (NumberFormatException e) { guildId = 0; }
        if (actor == null || !manages(actor, guildId)) {
            event.reply(actor == null ? "Unauthorized" : t(actor, "GuildManager.NotFound")).setEphemeral(true).queue();
            return;
        }
        int page = suffixInt(event.getComponentId());
        event.deferEdit().queue();
        showGuild(event.getMessage(), actor, guildId, page);
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (id.startsWith("guildSetupTransferSubmit-")) {
            transferSetup(event, suffixLong(id));
        } else if (id.startsWith("guildManagerTransferSubmit-")) {
            Parsed parsed = parse(id);
            transferConfigured(event, parsed);
        } else if (id.startsWith("guildManagerApplySubmit-")) {
            Parsed parsed = parse(id);
            submitApplication(event, parsed);
        }
    }

    private static void showEntry(Message message, UserObject actor, int page) {
        List<GuildObject> guilds = GuildRepository.getManagedBy(actor.getId());
        if (guilds.isEmpty()) {
            new UserControlManager(message, actor).loadStartPage();
        } else if (guilds.size() == 1) {
            showGuild(message, actor, guilds.getFirst().getGuildID(), 0);
        } else {
            showList(message, actor, guilds, page);
        }
    }

    private static void showList(Message message, UserObject actor, List<GuildObject> guilds, int requestedPage) {
        int pages = Math.max(1, (guilds.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int from = Math.min(page * PAGE_SIZE, guilds.size());
        List<GuildObject> displayed = guilds.subList(from, Math.min(from + PAGE_SIZE, guilds.size()));
        String entries = displayed.stream().map(guild -> t(actor, "GuildManager.List.Entry", Map.of(
                        "%name%", guildName(guild.getGuildID()),
                        "%partner%", onOff(actor, guild.isPartnerGuild()))))
                .reduce((a, b) -> a + "\n" + b).orElse("-");
        var embed = LanguageManager.getEmbedForUser("GuildManager.List", actor.getId(), new HashMap<>(Map.of(
                "%count%", String.valueOf(guilds.size()), "%page%", String.valueOf(page + 1),
                "%pages%", String.valueOf(pages), "%guilds%", entries))).build();
        List<ActionRow> rows = new ArrayList<>();
        rows.add(ActionRow.of(StringSelectMenu.create("guildManagerSelect-" + page)
                .setPlaceholder(t(actor, "GuildManager.List.Select"))
                .addOptions(displayed.stream().map(guild -> SelectOption.of(
                        shorten(guildName(guild.getGuildID()), 100), String.valueOf(guild.getGuildID()))).toList()).build()));
        if (pages > 1) rows.add(ActionRow.of(
                Button.secondary("guildManagerPage-" + Math.max(0, page - 1), t(actor, "General.Previous")).withDisabled(page == 0),
                Button.secondary("guildManagerPage-" + Math.min(pages - 1, page + 1), t(actor, "General.Next")).withDisabled(page + 1 >= pages)));
        rows.add(ActionRow.of(Button.primary("mainPage", t(actor, "UserProfile.Button.Back"))));
        message.editMessageEmbeds(embed).setComponents(rows).queue();
    }

    private static void showGuild(Message message, UserObject actor, long guildId, int returnPage) {
        GuildObject configured = GuildRepository.get(guildId);
        if (configured == null || configured.getManagerUserId() != actor.getId()) {
            showEntry(message, actor, returnPage); return;
        }
        GuildRepository.GuildStats stats = GuildRepository.stats(guildId, actor.getId());
        int threshold = ConfigManager.getInt("PartnerGuildUserCreations",
                ConfigManager.getInt("CreatedUserToBecomePartnerGuild", 30));
        int remaining = Math.max(0, threshold - stats.accountCount());
        if (!configured.isPartnerGuild() && remaining == 0
                && PartnerGuildApplicationRepository.get(guildId) == null)
            PartnerGuildApplicationRepository.inviteIfAbsent(guildId);
        PartnerGuildApplicationRepository.Application application = PartnerGuildApplicationRepository.get(guildId);
        Guild guild = Main.jda == null ? null : Main.jda.getGuildById(guildId);
        GuildRepository.LobbyUsage usage = GuildRepository.lobbyUsage(guildId);
        String permissionState = guild == null ? t(actor, "GuildManager.DiscordUnavailable")
                : PartnerGuildService.missingPermissions(guild).isEmpty() ? t(actor, "GuildManager.PermissionsReady")
                : t(actor, "GuildManager.PermissionsMissing", Map.of("%permissions%",
                        PartnerGuildService.permissionNames(PartnerGuildService.missingPermissions(guild), actor.getLanguage())));
        String category = "-";
        if (configured.getMygVoiceCategoryId() > 0) {
            Category found = guild == null ? null : guild.getCategoryById(configured.getMygVoiceCategoryId());
            category = found == null ? "`" + configured.getMygVoiceCategoryId() + "`"
                    : found.getName() + " (`" + found.getId() + "`)";
        }
        HashMap<String, String> values = new HashMap<>();
        values.put("%name%", guildName(guildId));
        values.put("%guildId%", String.valueOf(guildId));
        values.put("%owner%", guild == null ? "-" : "<@" + guild.getOwnerId() + ">");
        values.put("%members%", guild == null ? "-" : String.valueOf(guild.getMemberCount()));
        values.put("%accounts%", String.valueOf(stats.accountCount()));
        values.put("%rating%", rating(stats.averageRating(), actor.getLanguage()));
        values.put("%threshold%", String.valueOf(threshold));
        values.put("%remaining%", String.valueOf(remaining));
        values.put("%partner%", onOff(actor, configured.isPartnerGuild()));
        values.put("%partnerStatus%", applicationStatus(actor, application));
        values.put("%hostedLobbies%", configured.isPartnerGuild() ? String.valueOf(usage.hostedLobbies()) : "-");
        values.put("%activeLobbies%", String.valueOf(usage.activeLobbies()));
        values.put("%language%", configured.getLanguage().name());
        values.put("%addedAt%", configured.getAddedAt() == null ? "-" : "<t:" + configured.getAddedAt().getTime() / 1000 + ":F>");
        values.put("%textChannel%", configured.getMygTextChannelId() <= 0 ? "-" : "<#" + configured.getMygTextChannelId() + ">");
        values.put("%voiceCategory%", category);
        values.put("%permissions%", permissionState);
        List<ActionRow> rows = new ArrayList<>();
        List<Button> partnerActions = new ArrayList<>();
        if (configured.isPartnerGuild()) {
            partnerActions.add(Button.danger("guildManagerWithdraw-" + guildId + "-" + returnPage,
                    t(actor, "GuildManager.Partner.Withdraw")));
        } else if (application != null && (application.status() == PartnerGuildApplicationRepository.Status.INVITED
                || application.status() == PartnerGuildApplicationRepository.Status.REJECTED
                || application.status() == PartnerGuildApplicationRepository.Status.WITHDRAWN)) {
            partnerActions.add(Button.success("guildManagerApply-" + guildId + "-" + returnPage,
                    t(actor, "PartnerProgram.Eligible.Apply")));
        } else if (application != null && application.status() == PartnerGuildApplicationRepository.Status.ADMIN_APPROVED) {
            partnerActions.add(Button.success("partnerProgramConfirm-" + guildId,
                    t(actor, "PartnerProgram.Confirmation.Confirm")));
        }
        if (!partnerActions.isEmpty()) rows.add(ActionRow.of(partnerActions));
        rows.add(ActionRow.of(Button.secondary("guildManagerTransfer-" + guildId + "-" + returnPage,
                t(actor, "GuildManager.Transfer.Button"))));
        rows.add(ActionRow.of(Button.primary(GuildRepository.getManagedBy(actor.getId()).size() > 1
                        ? "guildManagerPage-" + returnPage : "mainPage", t(actor, "UserProfile.Button.Back"))));
        message.editMessageEmbeds(LanguageManager.getEmbedForUser("GuildManager.Detail", actor.getId(), values).build())
                .setComponents(rows).queue();
    }

    private static void openTransferModal(ButtonInteractionEvent event, long guildId, boolean setup, int page) {
        UserObject actor = UserController.get(event.getUser().getIdLong());
        Language language = actor == null ? Language.EN : actor.getLanguage();
        boolean allowed = setup ? GuildRepository.get(guildId) == null
                && GuildSetupAuthorizationRepository.claimIfAbsent(guildId, event.getUser().getIdLong())
                : actor != null && manages(actor, guildId);
        if (!allowed) {
            event.reply(LanguageManager.getMessageByLanguage("GuildManager.Transfer.NotAuthorized", language))
                    .setEphemeral(true).queue(); return;
        }
        String modalId = setup ? "guildSetupTransferSubmit-" + guildId
                : "guildManagerTransferSubmit-" + guildId + "-" + page;
        event.replyModal(Modal.create(modalId, LanguageManager.getMessageByLanguage("GuildManager.Transfer.Title", language))
                .addComponents(Label.of(LanguageManager.getMessageByLanguage("GuildManager.Transfer.Username", language),
                        TextInput.create("username", TextInputStyle.SHORT).setRequired(true).setMaxLength(30).build())).build()).queue();
    }

    private static void transferSetup(ModalInteractionEvent event, long guildId) {
        Language language = Language.EN;
        UserObject actor = UserController.get(event.getUser().getIdLong());
        if (actor != null) language = actor.getLanguage();
        if (GuildRepository.get(guildId) != null
                || !GuildSetupAuthorizationRepository.isAuthorized(guildId, event.getUser().getIdLong())) {
            event.reply(LanguageManager.getMessageByLanguage("GuildManager.Transfer.NotAuthorized", language)).setEphemeral(true).queue(); return;
        }
        UserObject target = target(event);
        if (target == null) {
            event.reply(LanguageManager.getMessageByLanguage("GuildManager.Transfer.UserNotFound", language)).setEphemeral(true).queue(); return;
        }
        Guild guild = Main.jda == null ? null : Main.jda.getGuildById(guildId);
        if (Main.jda == null) {
            event.reply(LanguageManager.getMessageByLanguage("GuildManager.Transfer.Failed", language)).setEphemeral(true).queue(); return;
        }
        Language responseLanguage = language;
        String guildName = guild == null ? String.valueOf(guildId) : guild.getName();
        event.deferReply(true).queue(hook -> Main.jda.retrieveUserById(target.getDiscordID()).queue(
                user -> user.openPrivateChannel().queue(dm ->
                        dm.sendMessageEmbeds(LanguageManager.getEmbedForUser("NewGuild.SetupGuild", target.getId(),
                                        new HashMap<>(Map.of("%guildName%", guildName))).build())
                                .setComponents(ActionRow.of(Button.secondary("setupGuild-" + guildId,
                                        t(target, "NewGuild.SetupGuild.Button")))).queue(sent -> {
                                    if (!GuildSetupAuthorizationRepository.authorize(guildId, target.getDiscordID())) {
                                        sent.delete().queue();
                                        hook.editOriginal(LanguageManager.getMessageByLanguage(
                                                "GuildManager.Transfer.Failed", responseLanguage)).queue();
                                        return;
                                    }
                                    DiscordLogService.action("GUILD_SETUP_TRANSFER", "Die Einrichtung von Guild " + guildId
                                            + " wurde an " + target.getUsername() + " (#" + target.getId() + ") übertragen");
                                    hook.editOriginal(LanguageManager.getMessageByLanguage(
                                                    "GuildManager.Transfer.Success", responseLanguage)
                                            .replace("%username%", target.getUsername())).queue();
                                    if (event.getMessage() != null) event.getMessage().delete().queue();
                                }, error -> hook.editOriginal(LanguageManager.getMessageByLanguage(
                                        "GuildManager.Transfer.TargetUnavailable", responseLanguage)).queue()),
                        error -> hook.editOriginal(LanguageManager.getMessageByLanguage(
                                "GuildManager.Transfer.TargetUnavailable", responseLanguage)).queue()),
                error -> hook.editOriginal(LanguageManager.getMessageByLanguage(
                        "GuildManager.Transfer.TargetUnavailable", responseLanguage)).queue()));
    }

    private static void transferConfigured(ModalInteractionEvent event, Parsed parsed) {
        UserObject actor = UserController.get(event.getUser().getIdLong());
        if (actor == null || parsed == null || !manages(actor, parsed.guildId())) {
            event.reply(actor == null ? "Unauthorized" : t(actor, "GuildManager.Transfer.NotAuthorized")).setEphemeral(true).queue(); return;
        }
        UserObject target = target(event);
        if (target == null) { event.reply(t(actor, "GuildManager.Transfer.UserNotFound")).setEphemeral(true).queue(); return; }
        if (target.getId() == actor.getId()) { event.reply(t(actor, "GuildManager.Transfer.SameUser")).setEphemeral(true).queue(); return; }
        if (!GuildRepository.transferManager(parsed.guildId(), actor.getId(), target.getId())) {
            event.reply(t(actor, "GuildManager.Transfer.Failed")).setEphemeral(true).queue(); return;
        }
        DiscordLogService.action("GUILD_MANAGER_TRANSFER", actor.getUsername() + " (#" + actor.getId()
                + ") hat Guild " + parsed.guildId() + " an " + target.getUsername() + " (#" + target.getId() + ") übertragen");
        notifyNewManager(target, parsed.guildId());
        ManagementMessageUpdater.refreshMainPage(actor.getId());
        ManagementMessageUpdater.refreshMainPage(target.getId());
        event.reply(t(actor, "GuildManager.Transfer.Success", Map.of("%username%", target.getUsername())))
                .setEphemeral(true).queue();
        if (event.getMessage() != null) new UserControlManager(event.getMessage(), actor).loadStartPage();
    }

    private static void submitApplication(ModalInteractionEvent event, Parsed parsed) {
        UserObject actor = UserController.get(event.getUser().getIdLong());
        if (actor == null || parsed == null || !canApply(actor, parsed.guildId())) {
            event.reply(actor == null ? "Unauthorized" : t(actor, "PartnerProgram.Invalid")).setEphemeral(true).queue(); return;
        }
        String note = event.getValue("note") == null ? "" : event.getValue("note").getAsString().trim();
        boolean submitted = PartnerGuildApplicationRepository.submit(parsed.guildId(), note);
        event.reply(t(actor, submitted ? "PartnerProgram.Application.Sent" : "PartnerProgram.Invalid"))
                .setEphemeral(true).queue();
        if (submitted) {
            PartnerGuildService.notifyAdminsOfApplication(parsed.guildId(), note);
            if (event.getMessage() != null) showGuild(event.getMessage(), actor, parsed.guildId(), parsed.page());
        }
    }

    private static void notifyNewManager(UserObject target, long guildId) {
        if (Main.jda == null) return;
        Main.jda.retrieveUserById(target.getDiscordID()).queue(user -> user.openPrivateChannel().queue(dm ->
                dm.sendMessageEmbeds(new EmbedCreator().setTitle(t(target, "GuildManager.Transfer.Notification.Title"))
                                .setDescription(t(target, "GuildManager.Transfer.Notification.Description",
                                        Map.of("%guild%", guildName(guildId)))).build())
                        .setComponents(ActionRow.of(Button.danger("delete", t(target, "General.Button.DeleteMessage")))).queue()));
    }

    private static UserObject target(ModalInteractionEvent event) {
        if (event.getValue("username") == null) return null;
        UserObject target = UserController.get(event.getValue("username").getAsString().trim());
        return target == null || target.isAnonymized() ? null : target;
    }

    private static boolean canApply(UserObject actor, long guildId) {
        if (!manages(actor, guildId)) return false;
        GuildObject guild = GuildRepository.get(guildId);
        if (guild == null || guild.isPartnerGuild()) return false;
        PartnerGuildApplicationRepository.Application application = PartnerGuildApplicationRepository.get(guildId);
        return application != null && (application.status() == PartnerGuildApplicationRepository.Status.INVITED
                || application.status() == PartnerGuildApplicationRepository.Status.REJECTED
                || application.status() == PartnerGuildApplicationRepository.Status.WITHDRAWN);
    }

    private static boolean manages(UserObject actor, long guildId) {
        GuildObject guild = GuildRepository.get(guildId);
        return actor != null && guild != null && guild.getManagerUserId() == actor.getId();
    }

    private static String applicationStatus(UserObject actor, PartnerGuildApplicationRepository.Application application) {
        return application == null ? t(actor, "GuildManager.Status.NOT_ELIGIBLE")
                : t(actor, "GuildManager.Status." + application.status().name());
    }

    private static String rating(Double rating, Language language) {
        if (rating == null) return "-";
        Locale locale = language == Language.DE ? Locale.GERMANY : Locale.US;
        return RatingFormatter.stars(rating) + " (" + String.format(locale, "%.2f", rating) + ")";
    }

    private static Parsed parse(String id) {
        String[] parts = id.split("-");
        if (parts.length < 3) return null;
        try { return new Parsed(Long.parseLong(parts[parts.length - 2]), Integer.parseInt(parts[parts.length - 1])); }
        catch (NumberFormatException e) { return null; }
    }

    private static long suffixLong(String id) { try { return Long.parseLong(id.substring(id.lastIndexOf('-') + 1)); } catch (Exception e) { return 0; } }
    private static int suffixInt(String id) { try { return Integer.parseInt(id.substring(id.lastIndexOf('-') + 1)); } catch (Exception e) { return 0; } }
    private static String guildName(long guildId) { Guild guild = Main.jda == null ? null : Main.jda.getGuildById(guildId); return guild == null ? String.valueOf(guildId) : guild.getName(); }
    private static String onOff(UserObject actor, boolean value) { return t(actor, value ? "General.On" : "General.Off"); }
    private static String shorten(String value, int max) { return value.length() <= max ? value : value.substring(0, max - 1) + "…"; }
    private static String t(UserObject user, String key) { return LanguageManager.getMessageByLanguage(key, user.getLanguage()); }
    private static String t(UserObject user, String key, Map<String, String> replacements) {
        String value = t(user, key);
        for (Map.Entry<String, String> entry : replacements.entrySet()) value = value.replace(entry.getKey(), entry.getValue());
        return value;
    }

    private record Parsed(long guildId, int page) {}
}
