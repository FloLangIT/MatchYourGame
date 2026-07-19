package de.flolang.matchyourgame.manager;

import de.flolang.matchyourgame.config.ConfigManager;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.database.user.UserRole;

public final class AdminAccess {
    private AdminAccess() {}

    public static boolean isProjectLeader(long discordId) {
        return String.valueOf(discordId).equals(ConfigManager.getString("Discord.ProjectLeaderID", ""));
    }

    public static UserRole role(UserObject user) {
        if (user == null) return UserRole.USER;
        return isProjectLeader(user.getDiscordID()) ? UserRole.ADMIN : user.getRole();
    }

    public static UserObject panelUser(long discordId) {
        UserObject user = UserRepository.get(discordId);
        return user != null && role(user) != UserRole.USER ? user : null;
    }

    public static boolean canManageUsers(UserObject user) { return role(user) != UserRole.USER; }
    public static boolean canViewGuilds(UserObject user) { return role(user) != UserRole.USER; }
    public static boolean canManagePartners(UserObject user) { return role(user) == UserRole.ADMIN; }
    public static boolean canInviteUsers(UserObject user) { return role(user) == UserRole.ADMIN; }
    public static boolean canManageGames(UserObject user) { return role(user) == UserRole.ADMIN; }
    public static boolean canAssignAdmins(UserObject user) {
        return user != null && isProjectLeader(user.getDiscordID());
    }
    public static boolean canAssignModerators(UserObject user) { return role(user) == UserRole.ADMIN; }
}
