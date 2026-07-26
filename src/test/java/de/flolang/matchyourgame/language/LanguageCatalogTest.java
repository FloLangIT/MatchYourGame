package de.flolang.matchyourgame.language;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LanguageCatalogTest {
    private static final Set<String> FEATURE_ROOTS = Set.of(
            "UserProfile", "FriendRequest", "Lobby", "GameProfile", "PassiveQ", "Party", "Match",
            "Review", "Admin", "Tutorial");

    @Test
    void germanAndEnglishFeatureCatalogsHaveIdenticalKeys() throws Exception {
        Map<String, Object> german = load("DE");
        Map<String, Object> english = load("EN");
        for (String root : FEATURE_ROOTS) {
            assertNotNull(german.get(root), "Missing DE root " + root);
            assertNotNull(english.get(root), "Missing EN root " + root);
            assertEquals(flatten(german.get(root), root), flatten(english.get(root), root),
                    "Language keys differ below " + root);
        }
    }

    @Test
    void generalOnAndOffRemainStringKeysInYaml() throws Exception {
        for (String language : new String[]{"DE", "EN"}) {
            Map<String, Object> general = nestedMap(load(language), "General");
            assertNotNull(general.get("On"), "Missing " + language + " General.On");
            assertNotNull(general.get("Off"), "Missing " + language + " General.Off");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String language) throws Exception {
        try (FileInputStream input = new FileInputStream("language/" + language + ".yml")) {
            return new Yaml().load(input);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }

    private static Set<String> flatten(Object value, String path) {
        Set<String> paths = new TreeSet<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet())
                paths.addAll(flatten(entry.getValue(), path + "." + entry.getKey()));
        } else paths.add(path);
        return paths;
    }
}
