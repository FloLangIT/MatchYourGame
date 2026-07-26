package de.flolang.matchyourgame.language;

import java.util.Locale;
import java.util.List;
import java.util.Map;

public final class CommunicationLanguageNames {
    private static final List<String> SUPPORTED_CODES = List.of(
            "DE", "EN", "FR", "ES", "IT",
            "PT", "NL", "PL", "RU", "UK",
            "TR", "AR", "ZH", "JA", "KO",
            "HI", "CS", "DA", "FI", "NO",
            "SV", "EL", "RO", "HU", "BG");
    private static final List<String> ADDITIONAL_CODES = List.of(
            "AF", "SQ", "HY", "AZ", "EU", "BE", "BN", "BS", "CA", "HR",
            "ET", "FA", "GL", "HE", "ID", "IS", "KA", "KK", "LV", "LT",
            "MK", "MS", "MT", "SR", "SK", "SL", "SW", "TA", "TH", "UR",
            "UZ", "VI");
    private static final Map<String, String> FLAGS = Map.ofEntries(
            Map.entry("DE", "🇩🇪"), Map.entry("EN", "🇬🇧"), Map.entry("FR", "🇫🇷"),
            Map.entry("ES", "🇪🇸"), Map.entry("IT", "🇮🇹"), Map.entry("PT", "🇵🇹"),
            Map.entry("NL", "🇳🇱"), Map.entry("PL", "🇵🇱"), Map.entry("RU", "🇷🇺"),
            Map.entry("UK", "🇺🇦"), Map.entry("TR", "🇹🇷"), Map.entry("AR", "🇸🇦"),
            Map.entry("ZH", "🇨🇳"), Map.entry("JA", "🇯🇵"), Map.entry("KO", "🇰🇷"),
            Map.entry("HI", "🇮🇳"), Map.entry("CS", "🇨🇿"), Map.entry("DA", "🇩🇰"),
            Map.entry("FI", "🇫🇮"), Map.entry("NO", "🇳🇴"), Map.entry("SV", "🇸🇪"),
            Map.entry("EL", "🇬🇷"), Map.entry("RO", "🇷🇴"), Map.entry("HU", "🇭🇺"),
            Map.entry("BG", "🇧🇬"), Map.entry("AF", "🇿🇦"), Map.entry("SQ", "🇦🇱"),
            Map.entry("HY", "🇦🇲"), Map.entry("AZ", "🇦🇿"), Map.entry("EU", "🇪🇸"),
            Map.entry("BE", "🇧🇾"), Map.entry("BN", "🇧🇩"), Map.entry("BS", "🇧🇦"),
            Map.entry("CA", "🇪🇸"), Map.entry("HR", "🇭🇷"), Map.entry("ET", "🇪🇪"),
            Map.entry("FA", "🇮🇷"), Map.entry("GL", "🇪🇸"), Map.entry("HE", "🇮🇱"),
            Map.entry("ID", "🇮🇩"), Map.entry("IS", "🇮🇸"), Map.entry("KA", "🇬🇪"),
            Map.entry("KK", "🇰🇿"), Map.entry("LV", "🇱🇻"), Map.entry("LT", "🇱🇹"),
            Map.entry("MK", "🇲🇰"), Map.entry("MS", "🇲🇾"), Map.entry("MT", "🇲🇹"),
            Map.entry("SR", "🇷🇸"), Map.entry("SK", "🇸🇰"), Map.entry("SL", "🇸🇮"),
            Map.entry("SW", "🇰🇪"), Map.entry("TA", "🇮🇳"), Map.entry("TH", "🇹🇭"),
            Map.entry("UR", "🇵🇰"), Map.entry("UZ", "🇺🇿"), Map.entry("VI", "🇻🇳"));

    private CommunicationLanguageNames() {}

    public static List<String> supportedCodes() {
        return java.util.stream.Stream.concat(SUPPORTED_CODES.stream(), ADDITIONAL_CODES.stream()).toList();
    }

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

    public static String flag(String code) {
        if (code == null) return "🏳️";
        return FLAGS.getOrDefault(code.trim().toUpperCase(Locale.ROOT), "🏳️");
    }

    public static String displayNameWithFlag(String code, Language displayLanguage) {
        return flag(code) + " " + displayName(code, displayLanguage);
    }
}
