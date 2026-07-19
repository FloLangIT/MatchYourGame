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
}
