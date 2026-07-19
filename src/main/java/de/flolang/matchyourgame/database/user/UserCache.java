package de.flolang.matchyourgame.database.user;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class UserCache {

    private static final Map<Integer, UserObject> BY_ID = new ConcurrentHashMap<>();
    private static final Map<String, UserObject> BY_USERNAME = new ConcurrentHashMap<>();
    private static final Map<Long, UserObject> BY_DISCORD_ID = new ConcurrentHashMap<>();


    public static UserObject get(int id) {
        return BY_ID.get(id);
    }

    public static UserObject get(String username) {
        if (username == null) return null;
        return BY_USERNAME.get(username.toLowerCase());
    }

    public static UserObject get(long discordId) {
        return BY_DISCORD_ID.get(discordId);
    }

    public static void put(UserObject user) {
        if (user == null) return;

        BY_ID.put(user.getId(), user);
        BY_USERNAME.put(user.getUsername().toLowerCase(), user);
        BY_DISCORD_ID.put(user.getDiscordID(), user);
    }

    public static void evict(UserObject user) {
        if (user == null) return;
        BY_ID.remove(user.getId());
        BY_USERNAME.remove(user.getUsername().toLowerCase());
        BY_DISCORD_ID.remove(user.getDiscordID());
    }

    public static void evictById(int userId) {
        evict(BY_ID.get(userId));
    }
}
