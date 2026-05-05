package de.flolang.matchyourgame.database.guild;

import de.flolang.matchyourgame.database.user.UserCache;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.language.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GuildController {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuildController.class);

    public static GuildObject getByGuildID(long guildID) {
        GuildObject cached = GuildCache.get(guildID);
        if (cached != null) {
            LOGGER.trace("Guild {} from cache", guildID);
            return cached;
        }

        GuildObject guild = GuildRepository.get(guildID);
        if (guild != null) {
            GuildCache.put(guild);
            LOGGER.trace("Guild {} from repository and cached", guildID);
        }

        return guild;
    }

    public static GuildObject create(long guildID, int managerUser, long mygVoiceCategoryId, long mygTextChannelId, Language language) {
        if(getByGuildID(guildID) != null) {
            throw new RuntimeException("Username or discordID already taken");
        }
        GuildObject guild = GuildRepository.createGuild(guildID, managerUser, mygVoiceCategoryId, mygTextChannelId, language);
        if (guild != null) {
            GuildCache.put(guild);
            LOGGER.debug("Created guild {} and stored in cache", guildID);
        }
        return guild;
    }

    public static void update(UserObject userObject) {
        UserRepository.update(userObject);
        UserCache.put(userObject);
        LOGGER.debug("Updated user {} and refreshed cache", userObject.getId());
    }


}
