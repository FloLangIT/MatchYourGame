package de.flolang.matchyourgame.manager;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.config.ConfigManager;
import de.flolang.matchyourgame.database.guild.GuildObject;
import de.flolang.matchyourgame.database.guild.GuildRepository;
import de.flolang.matchyourgame.database.guild.PartnerGuildApplicationRepository;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.database.user.UserRole;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.language.Language;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class PartnerGuildService {
    // KICK_MEMBERS is required by Discord to create invites which bypass "Apply to Join".
    // Discord does not expose that access-mode change through a documented guild event, so
    // partner guilds must retain the permission to keep lobby invites working after a switch.
    public static final EnumSet<Permission> REQUIRED_PERMISSIONS = EnumSet.of(
            Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL, Permission.MANAGE_PERMISSIONS,
            Permission.CREATE_INSTANT_INVITE, Permission.KICK_MEMBERS, Permission.VIEW_AUDIT_LOGS);

    private PartnerGuildService() {}

    public static void checkEligibility(long guildId) {
        if (Main.jda == null || guildId <= 0) return;
        GuildObject configured = GuildRepository.get(guildId);
        if (configured == null || configured.isPartnerGuild()) return;
        int threshold = ConfigManager.getInt("PartnerGuildUserCreations",
                ConfigManager.getInt("CreatedUserToBecomePartnerGuild", 30));
        if (GuildRepository.stats(guildId, configured.getManagerUserId()).accountCount() < threshold) return;
        if (!PartnerGuildApplicationRepository.inviteIfAbsent(guildId)) return;
        UserObject manager = UserRepository.get(configured.getManagerUserId());
        if (manager == null) return;
        send(manager, "PartnerProgram.Eligible.Title", "PartnerProgram.Eligible.Description",
                Map.of("%guild%", guildName(guildId), "%count%", String.valueOf(threshold)),
                ActionRow.of(Button.success("partnerProgramApply-" + guildId,
                        t(manager, "PartnerProgram.Eligible.Apply")), delete(manager)));
    }

    public static boolean inviteByAdmin(UserObject admin, long guildId) {
        if (admin == null || AdminAccess.role(admin) != UserRole.ADMIN) return false;
        GuildObject configured = GuildRepository.get(guildId);
        if (configured == null || configured.isPartnerGuild()) return false;
        PartnerGuildApplicationRepository.Application existing=PartnerGuildApplicationRepository.get(guildId);
        boolean invited=existing!=null&&(existing.status()==PartnerGuildApplicationRepository.Status.REJECTED
                || existing.status()==PartnerGuildApplicationRepository.Status.WITHDRAWN)
                ? PartnerGuildApplicationRepository.reinviteRejected(guildId)
                : PartnerGuildApplicationRepository.inviteIfAbsent(guildId);
        if(!invited)return false;
        UserObject manager = UserRepository.get(configured.getManagerUserId());
        if (manager != null) send(manager, "PartnerProgram.ManualInvite.Title",
                "PartnerProgram.ManualInvite.Description", Map.of("%guild%", guildName(guildId)),
                ActionRow.of(Button.success("partnerProgramApply-" + guildId,
                        t(manager, "PartnerProgram.Eligible.Apply")), delete(manager)));
        return manager != null;
    }

    public static void notifyAdminsOfApplication(long guildId, String note) {
        GuildObject configured = GuildRepository.get(guildId);
        if (configured == null) return;
        for (UserObject admin : administrators()) send(admin, "PartnerProgram.Admin.Title",
                "PartnerProgram.Admin.Description", Map.of("%guild%", guildName(guildId),
                        "%guildId%", String.valueOf(guildId), "%manager%", managerName(configured),
                        "%note%", note == null || note.isBlank() ? "-" : note),
                ActionRow.of(Button.success("partnerProgramApprove-" + guildId,
                                t(admin, "PartnerProgram.Admin.Approve")),
                        Button.danger("partnerProgramReject-" + guildId,
                                t(admin, "PartnerProgram.Admin.Reject")), delete(admin)));
    }

    public static boolean approve(UserObject admin, long guildId) {
        if (admin == null || AdminAccess.role(admin) != UserRole.ADMIN
                || !PartnerGuildApplicationRepository.approve(guildId, admin.getId())) return false;
        GuildObject configured = GuildRepository.get(guildId);
        UserObject manager = configured == null ? null : UserRepository.get(configured.getManagerUserId());
        if (manager != null) send(manager, "PartnerProgram.Confirmation.Title",
                "PartnerProgram.Confirmation.Description", Map.of("%guild%", guildName(guildId)),
                ActionRow.of(Button.success("partnerProgramConfirm-" + guildId,
                        t(manager, "PartnerProgram.Confirmation.Confirm")), delete(manager)));
        return true;
    }

    public static boolean reject(UserObject admin, long guildId) {
        if (admin == null || AdminAccess.role(admin) != UserRole.ADMIN
                || !PartnerGuildApplicationRepository.reject(guildId)) return false;
        GuildObject configured = GuildRepository.get(guildId);
        UserObject manager = configured == null ? null : UserRepository.get(configured.getManagerUserId());
        if (manager != null) send(manager, "PartnerProgram.Rejected.Title", "PartnerProgram.Rejected.Description",
                Map.of("%guild%", guildName(guildId)), ActionRow.of(delete(manager)));
        return true;
    }

    public static Set<Permission> missingPermissions(Guild guild) {
        if (guild == null) return EnumSet.copyOf(REQUIRED_PERMISSIONS);
        Member self = guild.getSelfMember();
        Set<Permission> missing = EnumSet.copyOf(REQUIRED_PERMISSIONS);
        missing.removeIf(self::hasPermission);
        return missing;
    }

    public static Set<Permission> missingPermissions(Guild guild, GuildChannel channel) {
        if(guild==null||channel==null)return EnumSet.copyOf(REQUIRED_PERMISSIONS);
        Member self=guild.getSelfMember();
        Set<Permission> missing=EnumSet.copyOf(REQUIRED_PERMISSIONS);
        missing.removeIf(permission->permission==Permission.KICK_MEMBERS
                ? self.hasPermission(permission):self.hasPermission(channel,permission));
        return missing;
    }

    public static String permissionNames(Set<Permission> permissions) {
        return permissions.stream().map(Permission::getName).reduce((a,b) -> a + ", " + b).orElse("-");
    }

    public static String permissionNames(Set<Permission> permissions, Language language) {
        return permissions.stream().map(permission -> LanguageManager.getMessageByLanguage(
                        "PartnerProgram.Permissions." + permission.name(), language))
                .reduce((a,b) -> a + ", " + b).orElse("-");
    }

    public static void notifyManagerProblem(GuildObject configured, String problem) {
        if (configured == null) return;
        UserObject manager = UserRepository.get(configured.getManagerUserId());
        if (manager == null) return;
        send(manager, "PartnerProgram.Problem.Title", "PartnerProgram.Problem.Description",
                Map.of("%guild%", guildName(configured.getGuildID()), "%problem%", problem),
                ActionRow.of(delete(manager)));
    }

    public static void notifyManagerProblem(GuildObject configured, String problemKey, Map<String,String> values) {
        if(configured==null)return;
        UserObject manager=UserRepository.get(configured.getManagerUserId());
        if(manager==null)return;
        notifyManagerProblem(configured,t(manager,problemKey,values));
    }

    public static void notifyActivationProblem(GuildObject configured, String problem) {
        if(configured==null)return;
        UserObject manager=UserRepository.get(configured.getManagerUserId());
        if(manager==null)return;
        send(manager,"PartnerProgram.ActivationProblem.Title","PartnerProgram.ActivationProblem.Description",
                Map.of("%guild%",guildName(configured.getGuildID()),"%problem%",problem),ActionRow.of(delete(manager)));
    }

    private static Set<UserObject> administrators() {
        Set<UserObject> admins = new LinkedHashSet<>();
        UserRepository.getAll().stream().filter(user -> AdminAccess.role(user) == UserRole.ADMIN).forEach(admins::add);
        String leaderId = ConfigManager.getString("Discord.ProjectLeaderID", "");
        try { UserObject leader = UserRepository.get(Long.parseLong(leaderId)); if (leader != null) admins.add(leader); }
        catch (NumberFormatException ignored) {}
        return admins;
    }

    private static void send(UserObject target, String titleKey, String descriptionKey,
                             Map<String,String> values, ActionRow row) {
        if (Main.jda == null || target == null) return;
        Main.jda.retrieveUserById(target.getDiscordID()).queue(user -> user.openPrivateChannel().queue(dm ->
                dm.sendMessageEmbeds(new EmbedCreator().setTitle(t(target,titleKey))
                                .setDescription(t(target,descriptionKey,values)).build())
                        .setComponents(row).queue()));
    }

    private static Button delete(UserObject user) { return Button.danger("delete", t(user,"General.Button.DeleteMessage")); }
    private static String guildName(long guildId) { Guild guild=Main.jda==null?null:Main.jda.getGuildById(guildId);return guild==null?String.valueOf(guildId):guild.getName(); }
    private static String managerName(GuildObject guild) { UserObject manager=UserRepository.get(guild.getManagerUserId());return manager==null?"-":manager.getUsername(); }
    private static String t(UserObject user,String key){return LanguageManager.getMessageByLanguage(key,user.getLanguage());}
    private static String t(UserObject user,String key,Map<String,String> values){String text=t(user,key);for(var entry:values.entrySet())text=text.replace(entry.getKey(),entry.getValue());return text;}
}
