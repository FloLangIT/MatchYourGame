package de.flolang.matchyourgame.language;

import java.util.Locale;

public final class CommunicationLanguageNames {
    private CommunicationLanguageNames() {}

    public static String displayName(String code, Language displayLanguage) {
        if (code == null || code.isBlank()) return "-";
        String normalized = code.trim();
        Locale language = Locale.forLanguageTag(normalized.replace('_', '-'));
        if (language.getLanguage().isBlank()) return normalized.toUpperCase(Locale.ROOT);
        Locale displayLocale = displayLanguage == Language.DE ? Locale.GERMAN : Locale.ENGLISH;
        String name = language.getDisplayLanguage(displayLocale);
        if (name.isBlank() || name.equalsIgnoreCase(language.getLanguage()))
            return normalized.toUpperCase(Locale.ROOT);
        return name.substring(0, 1).toUpperCase(displayLocale) + name.substring(1);
    }
}
