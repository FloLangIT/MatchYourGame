package de.flolang.matchyourgame.database.game;

import java.util.*;

public final class RankCompatibilityGroups {
    private RankCompatibilityGroups() {}

    public static Set<Pair> parse(String specification) {
        Set<Pair> pairs = new LinkedHashSet<>();
        if (specification == null || specification.isBlank() || specification.trim().equals("-")) return pairs;
        for (String rawGroup : specification.split(";")) {
            List<String> group = Arrays.stream(rawGroup.split("\\|"))
                    .map(String::trim).filter(name -> !name.isBlank()).toList();
            if (group.size() < 2) throw new IllegalArgumentException("Every compatibility group needs at least two ranks");
            for (String source : group) for (String target : group)
                if (!source.equalsIgnoreCase(target)) pairs.add(new Pair(normalize(source), normalize(target)));
        }
        return pairs;
    }

    private static String normalize(String value) { return value.toLowerCase(Locale.ROOT); }

    public record Pair(String source, String target) {}
}
