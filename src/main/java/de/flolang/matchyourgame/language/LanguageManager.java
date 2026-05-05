package de.flolang.matchyourgame.language;

import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.embed.EmbedCreator;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

public class LanguageManager {

    public static String getMessageForUser(String messageKey, int id) {
        Language language = UserController.get(id).getLanguage();
        return getMessageByLanguage(messageKey, language);
    }

    public static String getMessageByLanguage(String messageKey, Language language) {
        File langFile = new File("language/" + language.name() + ".yml");
        if (!langFile.exists()) return messageKey + " Language not found: " + language.name();
        final Map<String, Object> langData;
        Yaml yaml = new Yaml();
        try (InputStream input = new FileInputStream(langFile)) {
            langData = yaml.load(input);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String[] keys = messageKey.split("\\.");
        Object current = langData;

        for (String key : keys) {
            if (current instanceof Map<?, ?> map && map.containsKey(key)) {
                current = map.get(key);
            } else {
                return messageKey + " Path not found";
            }
        }

        return current instanceof String ? (String) current : messageKey + " Path not found";
    }

    public static EmbedCreator getEmbedForUser(String messageKey, int id, HashMap<String, String> replacings) {
        Language language = UserController.get(id).getLanguage();
        return getEmbedByLanguage(messageKey, language, replacings);
    }

    public static EmbedCreator getEmbedByLanguage(String messageKey, Language language, HashMap<String, String> replacings) {
        String title = LanguageManager.getMessageByLanguage(messageKey + ".title", language);
        String description = LanguageManager.getMessageByLanguage(messageKey + ".description", language);
        String footer = LanguageManager.getMessageByLanguage(messageKey + ".footer", language);
        for (HashMap.Entry<String, String> entry : replacings.entrySet()) {
            description = description.replace(entry.getKey(), entry.getValue());
            title = title.replace(entry.getKey(), entry.getValue());
            footer = footer.replace(entry.getKey(), entry.getValue());
        }
        EmbedCreator embedBuilder = new EmbedCreator()
                .setTitle(title)
                .setDescription(description);
        if(!footer.contains("Path not found")) embedBuilder.setFooter(footer);
        return embedBuilder;
    }

}
