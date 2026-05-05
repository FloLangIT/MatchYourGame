package de.flolang.matchyourgame.database.user;

import de.flolang.matchyourgame.language.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserController {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserController.class);

    public static UserObject get(int id) {
        UserObject cached = UserCache.get(id);
        if (cached != null) {
            LOGGER.trace("User {} from cache", id);
            return cached;
        }

        UserObject user = UserRepository.get(id);
        if (user != null) {
            UserCache.put(user);
            LOGGER.trace("User {} from repository and cached", id);
        }

        return user;
    }

    public static UserObject get(String username) {
        UserObject cached = UserCache.get(username);
        if (cached != null) {
            LOGGER.trace("User {} from cache", username);
            return cached;
        }

        UserObject user = UserRepository.get(username);
        if (user != null) {
            UserCache.put(user);
            LOGGER.trace("User {} from repository and cached", username);
        }

        return user;
    }

    public static UserObject get(long discordId) {
        UserObject cached = UserCache.get(discordId);
        if (cached != null) {
            LOGGER.trace("User {} from cache", discordId);
            return cached;
        }

        UserObject user = UserRepository.get(discordId);
        if (user != null) {
            UserCache.put(user);
            LOGGER.trace("User {} from repository and cached", discordId);
        }

        return user;
    }

    public static UserObject create(String username, long discordId, Language language) {
        if(get(username) != null || get(discordId) != null) {
            throw new RuntimeException("Username or discordID already taken");
        }
        UserObject user = UserRepository.createUser(username, discordId, language);
        if (user != null) {
            UserCache.put(user);
            LOGGER.debug("Created user {} and stored in cache", username);
        }
        return user;
    }

    public static void update(UserObject userObject) {
        UserRepository.update(userObject);
        UserCache.put(userObject);
        LOGGER.debug("Updated user {} and refreshed cache", userObject.getId());
    }

}
