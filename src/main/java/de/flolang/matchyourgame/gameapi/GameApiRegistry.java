package de.flolang.matchyourgame.gameapi;

import de.flolang.matchyourgame.gameapi.valorant.ValorantApiProvider;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GameApiRegistry {
    private static final Map<String, GameApiProvider> PROVIDERS = new LinkedHashMap<>();

    static {
        register(new ValorantApiProvider());
    }

    private GameApiRegistry() {}

    public static void register(GameApiProvider provider) {
        PROVIDERS.put(provider.id().toUpperCase(java.util.Locale.ROOT), provider);
    }

    public static GameApiProvider get(String id) {
        return id == null ? null : PROVIDERS.get(id.toUpperCase(java.util.Locale.ROOT));
    }

    public static Collection<GameApiProvider> all() {
        return java.util.List.copyOf(PROVIDERS.values());
    }
}
