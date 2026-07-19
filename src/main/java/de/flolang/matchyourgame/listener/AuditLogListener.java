package de.flolang.matchyourgame.listener;

import de.flolang.matchyourgame.database.user.UserCache;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.logging.DiscordLogService;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AuditLogListener extends ListenerAdapter {
    private static final Map<String, String> ACTION_NAMES = actionNames();

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String action = event.getButton().getCustomId();
        if (hasPrefix(action, "passiveGlobal", "passiveSyncToggle", "gameWizardConfirm", "friendAccept",
                "friendDeny", "friendIgnore", "friendRemove", "partyInviteAccept", "partyInviteDecline",
                "partyLeave", "crewDeleteConfirm", "crewInviteAccept", "crewInviteDecline", "crewKick",
                "crewLeave", "lobbyTogglePassive", "lobbyInviteAccept", "lobbyInviteDecline", "lobbyClose",
                "lobbyDissolve", "lobbyStartCurrent", "lobbyLeave", "lobbySettingsRankRule", "matchConfirm",
                "matchReject", "reportDmEnd", "reportDelete", "adminGuildPartner", "adminUserUnban",
                "partnerProgramApprove", "partnerProgramReject", "partnerProgramConfirm", "guildManagerWithdraw"))
            logInteraction(event, "Änderung", action);
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String action = event.getComponentId();
        if (hasPrefix(action, "passiveProfileToggle", "profileMessageLanguage", "profileFriendRequestPolicy",
                "partyFriendSelect", "partyKickMember", "crewGameToggle", "crewRole", "lobbyClanSelect",
                "lobbyMergeSelect", "lobbyAddLanguageJoin", "adminUserRole"))
            logInteraction(event, "Änderung", action + " · Auswahl " + event.getValues());
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String action = event.getModalId();
        if (hasPrefix(action, "createAccount", "setupGuild", "communicationLanguageAdd", "profileUsername",
                "profileCommunicationLanguageAdd", "addFriend", "crewCreate", "crewInvite", "crewRename",
                "lobbyInviteFriend", "lobbyKickSubmit", "lobbySettingsSubmit", "reviewSubmit", "matchStats",
                "reportSubmit", "reportBanSubmit", "reportFinishSubmit", "reportDmReplySubmit",
                "adminUserBanSubmit", "adminUserDmSubmit", "adminUserWarnSubmit", "adminGameEditSubmit", "adminGameOptionsSubmit",
                "adminGameCreateSubmit", "profileDeleteAccountConfirm", "projectInviteTarget", "projectInviteDeclineReason",
                "partnerProgramApplySubmit", "guildManagerApplySubmit", "guildManagerTransferSubmit",
                "guildSetupTransferSubmit", "adminBroadcastSubmit"))
            logInteraction(event, "Änderung", action);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String action = "/" + event.getFullCommandName();
        if (hasPrefix(action, "/clear", "/game", "/review")) logInteraction(event, "Command", action);
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        DiscordLogService.action("GUILD", "Bot wurde zu Guild " + event.getGuild().getName()
                + " (" + event.getGuild().getId() + ") hinzugefügt");
    }

    private static void logInteraction(GenericInteractionCreateEvent event, String kind, String rawAction) {
        String action = describe(rawAction);
        UserObject account = UserCache.get(event.getUser().getIdLong());
        String guild = event.getGuild() == null ? "DM" : event.getGuild().getName()
                + " (" + event.getGuild().getId() + ")";
        String channel = event.getChannel() == null ? "-" : event.getChannel().getName()
                + " (" + event.getChannel().getId() + ")";
        DiscordLogService.action(kind, "Aktion: " + action + "\nDiscord: " + event.getUser().getName()
                + " (" + event.getUser().getId() + ")\nMYG-Account: "
                + (account == null ? "nicht vorhanden" : account.getUsername() + " (#" + account.getId() + ")")
                + "\nGuild: " + guild + "\nChannel: " + channel + "\nInteraction: " + rawAction);
    }

    private static String describe(String action) {
        if (action == null) return "Unbekannte Aktion";
        for (Map.Entry<String, String> entry : ACTION_NAMES.entrySet())
            if (action.startsWith(entry.getKey())) return entry.getValue();
        return action;
    }

    private static boolean hasPrefix(String action, String... prefixes) {
        if (action == null) return false;
        for (String prefix : prefixes) if (action.startsWith(prefix)) return true;
        return false;
    }

    private static Map<String, String> actionNames() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("passiveGlobalShortcut", "PassiveQ global umgeschaltet");
        names.put("passiveGlobalToggle", "PassiveQ global umgeschaltet");
        names.put("passiveProfileToggle", "PassiveQ für ein Spiel umgeschaltet");
        names.put("lobbyTogglePassive", "PassiveQ einer Lobby umgeschaltet");
        names.put("gameWizardConfirm", "Spielprofil/PassiveQ-Profil gespeichert");
        names.put("gameProfile", "Spielprofil verwaltet");
        names.put("createAccount", "Account-Erstellung");
        names.put("setupGuild", "Guild-Einrichtung");
        names.put("lobby", "Lobby-Aktion");
        names.put("party", "Party-Aktion");
        names.put("crew", "Crew-Aktion");
        names.put("friend", "Freundschaftsaktion");
        names.put("report", "Report-/Feedback-Aktion");
        names.put("review", "Bewertungsaktion");
        names.put("match", "Match-Aktion");
        names.put("profile", "Profil bearbeitet");
        names.put("admin", "Admin-Aktion");
        names.put("guildManager", "Guild-Verwaltung");
        names.put("guildSetupTransfer", "Guild-Einrichtung übertragen");
        names.put("adminBroadcast", "Broadcast versendet");
        return names;
    }
}
