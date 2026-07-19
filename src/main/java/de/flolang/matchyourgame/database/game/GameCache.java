package de.flolang.matchyourgame.database.game;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GameCache {

    private static final Map<Integer, GameObject> BY_ID = new ConcurrentHashMap();

    public static GameObject get(int id) {
        return BY_ID.get(id);
    }

    public static void add(GameObject gameObject) {
        BY_ID.put(gameObject.getId(), gameObject);
    }

    public static void remove(int id) {
        BY_ID.remove(id);
    }

}
