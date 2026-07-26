package de.flolang.matchyourgame.language;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommunicationLanguageNamesTest {
    @Test
    void localizesLanguageCodesUsingTheSelectedMessageLanguage() {
        assertEquals("Deutsch", CommunicationLanguageNames.displayName("DE", Language.DE));
        assertEquals("Englisch", CommunicationLanguageNames.displayName("EN", Language.DE));
        assertEquals("German", CommunicationLanguageNames.displayName("DE", Language.EN));
        assertEquals("English", CommunicationLanguageNames.displayName("EN", Language.EN));
    }

    @Test
    void prefixesLocalizedNamesWithTheConfiguredFlag() {
        assertEquals("🇩🇪 Deutsch",
                CommunicationLanguageNames.displayNameWithFlag("DE", Language.DE));
        assertEquals("🇬🇧 English",
                CommunicationLanguageNames.displayNameWithFlag("EN", Language.EN));
    }

    @Test
    void offersMoreThanOneDiscordSelectPage() {
        org.junit.jupiter.api.Assertions.assertTrue(
                CommunicationLanguageNames.supportedCodes().size() > 25);
    }
}
