package de.flolang.matchyourgame.database.guild;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GuildCache {

    private static final Map<Long, GuildObject> BY_GUILDID = new ConcurrentHashMap<>();
    private static final Map<Long, GuildObject> BY_CATEGORY = new ConcurrentHashMap<>();
    private static final Map<Long, GuildObject> BY_TEXTCHANNEL = new ConcurrentHashMap<>();

    public static GuildObject get(long guildID) {
        return BY_GUILDID.get(guildID);
    }

    public static GuildObject getCategory(long categoryID) {
        return BY_CATEGORY.get(categoryID);
    }

    public static GuildObject getTextChannel(long textChannelID) {
        return BY_TEXTCHANNEL.get(textChannelID);
    }

    public static void put(GuildObject guild) {
        BY_GUILDID.put(guild.getGuildID(), guild);
        BY_CATEGORY.put(guild.getGuildID(), guild);
        BY_TEXTCHANNEL.put(guild.getGuildID(), guild);
    }

}
