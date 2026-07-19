package de.flolang.matchyourgame.database.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

public class GameController {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameController.class);

    public static GameObject get(int id) {
        GameObject cached = GameCache.get(id);
        if (cached != null) {
            LOGGER.trace("Returning game from cache with id {}", id);
            return cached;
        }
        GameObject gameObject = GameRepository.get(id);
        if(gameObject != null) {
            GameCache.add(gameObject);
        }
        return gameObject;
    }

    public static ArrayList<GameObject> getSubGames(int id) {
        return GameRepository.getSubGames(id);
    }

    public static ArrayList<GameObject> getAllGames() {
        return GameRepository.getAllGames();
    }

    public static ArrayList<GameObject> getAllMainGames() {
        return GameRepository.getAllMainGames();
    }

    public static GameObject create(String name, boolean skillbased, int subGameFrom, boolean active) {
        GameObject gameObject = GameRepository.create(subGameFrom, name, skillbased, active);
        if(gameObject != null) {
            GameCache.add(gameObject);
        }
        return gameObject;
    }

    public static void update(GameObject gameObject) {
        GameRepository.update(gameObject);
        GameCache.add(gameObject);
        LOGGER.debug("Updated game {} and refreshed cache", gameObject.getId());
    }

    public static void delete(int id) {
        GameRepository.remove(id);
        GameCache.remove(id);
        LOGGER.debug("Deleted game with id {}", id);
    }
}
