package de.flolang.matchyourgame.database.game;

import java.util.ArrayList;
import java.util.List;

public final class GameStatDefinitionParser {
    private GameStatDefinitionParser() {}

    public static List<GameStatDefinition> parse(String input) {
        List<GameStatDefinition> definitions = new ArrayList<>();
        if (input == null || input.isBlank()) return definitions;
        for (String raw : input.trim().split("[\\s,]+")) {
            String[] parts = raw.split(":", 3);
            if (parts.length != 3 || parts[1].isBlank())
                throw new IllegalArgumentException("Ungültige Statistik '" + raw + "'. Erwartet: PLAYER|TEAM:Name:Datentyp");
            try {
                definitions.add(new GameStatDefinition(0, 0, parts[1].replace('_', ' '),
                        GameStatDefinition.Scope.valueOf(parts[0].toUpperCase()),
                        GameStatDefinition.ValueType.valueOf(parts[2].toUpperCase()), true));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Ungültige Statistik '" + raw +
                        "'. Bereiche: PLAYER/TEAM; Datentypen: INTEGER/DECIMAL/BOOLEAN/TEXT");
            }
        }
        return definitions;
    }
}
