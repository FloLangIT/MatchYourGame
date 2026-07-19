package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.guild.GuildObject;
import de.flolang.matchyourgame.database.guild.GuildRepository;
import de.flolang.matchyourgame.database.guild.PartnerGuildApplicationRepository;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.review.RatingFormatter;
import de.flolang.matchyourgame.manager.AdminAccess;
import de.flolang.matchyourgame.manager.PartnerGuildService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProjectGuildAdminListener extends ListenerAdapter {
    private static final int PAGE_SIZE = 23;

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.equals("adminGuilds") && !id.startsWith("adminGuildsPage-")
                && !id.startsWith("adminGuildOpen-") && !id.startsWith("adminGuildPartner-")) return;
        UserObject admin = admin(event.getUser().getIdLong());
        if (admin == null) {
            event.reply("Unauthorized").setEphemeral(true).queue();
            return;
        }
        if (id.equals("adminGuilds") || id.startsWith("adminGuildsPage-")) {
            int page = id.equals("adminGuilds") ? 0 : intSuffix(id);
            event.deferEdit().queue();
            showGuilds(event.getMessage(), admin, page);
            return;
        }
        ParsedId parsed = parseGuildAction(id);
        if (parsed == null) {
            event.reply(t(admin, "Admin.Guilds.NotFound")).setEphemeral(true).queue();
            return;
        }
        if (id.startsWith("adminGuildOpen-")) {
            event.deferEdit().queue();
            showGuild(event.getMessage(), admin, parsed.guildId(), parsed.page());
            return;
        }
        activatePartner(event, admin, parsed.guildId(), parsed.page());
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().startsWith("adminGuildSelect-") || event.getValues().isEmpty()) return;
        UserObject admin = admin(event.getUser().getIdLong());
        if (admin == null) {
            event.reply("Unauthorized").setEphemeral(true).queue();
            return;
        }
        long guildId;
        try {
            guildId = Long.parseLong(event.getValues().getFirst());
        } catch (NumberFormatException exception) {
            event.reply(t(admin, "Admin.Guilds.NotFound")).setEphemeral(true).queue();
            return;
        }
        int page = intSuffix(event.getComponentId());
        event.deferEdit().queue();
        showGuild(event.getMessage(), admin, guildId, page);
    }

    private static void showGuilds(Message message, UserObject admin, int requestedPage) {
        List<GuildObject> guilds = GuildRepository.getAll();
        int pages = Math.max(1, (guilds.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int from = Math.min(page * PAGE_SIZE, guilds.size());
        int to = Math.min(from + PAGE_SIZE, guilds.size());
        List<GuildObject> displayed = guilds.subList(from, to);
        Map<Long, GuildRepository.GuildStats> statistics = new HashMap<>();
        displayed.forEach(guild -> statistics.put(guild.getGuildID(), stats(guild)));
        String entries = displayed.stream().map(guild -> {
            GuildRepository.GuildStats stats = statistics.get(guild.getGuildID());
            return t(admin, "Admin.Guilds.Entry", Map.of(
                    "%name%", guildName(guild.getGuildID()),
                    "%accounts%", String.valueOf(stats.accountCount()),
                    "%partner%", onOff(admin, guild.isPartnerGuild())));
        }).reduce((first, next) -> first + "\n" + next).orElse(t(admin, "Admin.Guilds.None"));
        var embed = new EmbedCreator().setTitle(t(admin, "Admin.Guilds.Title"))
                .setDescription(t(admin, "Admin.Guilds.Description", Map.of(
                        "%count%", String.valueOf(guilds.size()),
                        "%page%", String.valueOf(page + 1),
                        "%pages%", String.valueOf(pages),
                        "%guilds%", entries))).build();
        List<ActionRow> rows = new ArrayList<>();
        if (!displayed.isEmpty()) {
            rows.add(ActionRow.of(StringSelectMenu.create("adminGuildSelect-" + page)
                    .setPlaceholder(t(admin, "Admin.Guilds.Select"))
                    .addOptions(displayed.stream().map(guild -> {
                        GuildRepository.GuildStats guildStats = statistics.get(guild.getGuildID());
                        return SelectOption.of(shorten(guildName(guild.getGuildID()), 100),
                                        String.valueOf(guild.getGuildID()))
                                .withDescription(shorten(guildStats.accountCount() + " Accounts · "
                                        + onOff(admin, guild.isPartnerGuild()), 100));
                    }).toList()).build()));
        }
        if (pages > 1) {
            rows.add(ActionRow.of(
                    Button.secondary("adminGuildsPage-" + Math.max(0, page - 1), t(admin, "General.Previous"))
                            .withDisabled(page == 0),
                    Button.secondary("adminGuildsPage-" + Math.min(pages - 1, page + 1), t(admin, "General.Next"))
                            .withDisabled(page >= pages - 1)));
        }
        rows.add(ActionRow.of(Button.primary("adminPanel", t(admin, "UserProfile.Button.Back"))));
        message.editMessageEmbeds(embed).setComponents(rows).queue();
    }

    private static void showGuild(Message message, UserObject admin, long guildId, int returnPage) {
        GuildObject configuredGuild = GuildRepository.get(guildId);
        if (configuredGuild == null) {
            showGuilds(message, admin, returnPage);
            return;
        }
        Guild discordGuild = Main.jda == null ? null : Main.jda.getGuildById(guildId);
        GuildRepository.GuildStats stats = stats(configuredGuild);
        UserObject manager = configuredGuild.getManagerUser();
        String voiceCategory = "-";
        if (configuredGuild.getMygVoiceCategoryId() > 0) {
            Category category = discordGuild == null ? null
                    : discordGuild.getCategoryById(configuredGuild.getMygVoiceCategoryId());
            voiceCategory = category == null ? String.valueOf(configuredGuild.getMygVoiceCategoryId())
                    : category.getName() + " (`" + category.getId() + "`)";
        }
        HashMap<String, String> values = new HashMap<>();
        values.put("%name%", guildName(guildId));
        values.put("%guildId%", String.valueOf(guildId));
        values.put("%host%", guildHost(discordGuild));
        values.put("%manager%", manager == null ? "-" : manager.getUsername() + " (MYG-ID " + manager.getId() + ")");
        values.put("%accounts%", String.valueOf(stats.accountCount()));
        values.put("%rating%", rating(stats.averageRating()));
        values.put("%partner%", onOff(admin, configuredGuild.isPartnerGuild()));
        PartnerGuildApplicationRepository.Application application=PartnerGuildApplicationRepository.get(guildId);
        values.put("%partnerStatus%",application==null?"-":application.status().name());
        values.put("%voiceCategory%", voiceCategory);
        values.put("%language%", configuredGuild.getLanguage().name());
        values.put("%addedAt%", discordTime(configuredGuild.getAddedAt()));
        message.editMessageEmbeds(LanguageManager.getEmbedForUser("Admin.Guilds.Detail", admin.getId(), values).build())
                .setComponents(
                        ActionRow.of(Button.success("adminGuildPartner-" + guildId + "-" + returnPage,
                                        t(admin, "Admin.Guilds.Partner.InviteButton"))
                                .withDisabled(configuredGuild.isPartnerGuild() || !AdminAccess.canManagePartners(admin)
                                        || application!=null&&application.status()!=PartnerGuildApplicationRepository.Status.REJECTED)),
                        ActionRow.of(Button.primary("adminGuildsPage-" + returnPage,
                                t(admin, "UserProfile.Button.Back"))))
                .queue();
    }

    private static void activatePartner(ButtonInteractionEvent event, UserObject admin, long guildId, int returnPage) {
        if (!AdminAccess.canManagePartners(admin)) {
            event.reply(t(admin, "Admin.Panel.NoPermission")).setEphemeral(true).queue();
            return;
        }
        boolean invited=PartnerGuildService.inviteByAdmin(admin,guildId);
        event.reply(t(admin,invited?"Admin.Guilds.Partner.Invited":"Admin.Guilds.Partner.InviteFailed"))
                .setEphemeral(true).queue();
        if(invited)showGuild(event.getMessage(),admin,guildId,returnPage);
    }

    private static GuildRepository.GuildStats stats(GuildObject guild) {
        return GuildRepository.stats(guild.getGuildID(), guild.getManagerUserId());
    }

    private static String guildName(long guildId) {
        Guild guild = Main.jda == null ? null : Main.jda.getGuildById(guildId);
        return guild == null ? String.valueOf(guildId) : guild.getName();
    }

    private static String guildHost(Guild guild) {
        if (guild == null) return "-";
        Member owner = guild.getOwner();
        return owner == null ? "`" + guild.getOwnerId() + "`"
                : "@" + owner.getUser().getName() + " (`" + owner.getId() + "`)";
    }

    private static UserObject admin(long discordId) {
        return AdminAccess.panelUser(discordId);
    }

    private static String rating(Double rating) {
        return rating == null ? "-" : RatingFormatter.stars(rating) + " ("
                + String.format(Locale.GERMANY, "%.2f", rating) + ")";
    }

    private static String onOff(UserObject user, boolean value) {
        return t(user, value ? "General.On" : "General.Off");
    }

    private static String discordTime(java.sql.Timestamp timestamp) {
        return timestamp == null ? "-" : "<t:" + timestamp.getTime() / 1000 + ":F>";
    }

    private static int intSuffix(String id) {
        try {
            return Integer.parseInt(id.substring(id.lastIndexOf('-') + 1));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static ParsedId parseGuildAction(String id) {
        String[] parts = id.split("-");
        if (parts.length < 3) return null;
        try {
            return new ParsedId(Long.parseLong(parts[parts.length - 2]),
                    Integer.parseInt(parts[parts.length - 1]));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String shorten(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum - 1) + "…";
    }

    private static String t(UserObject user, String key) {
        return LanguageManager.getMessageForUser(key, user.getId());
    }

    private static String t(UserObject user, String key, Map<String, String> replacements) {
        return LanguageManager.getMessageForUser(key, user.getId(), replacements);
    }

    private record ParsedId(long guildId, int page) {}
}
