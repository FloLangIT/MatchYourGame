package de.flolang.matchyourgame.database.user;

import de.flolang.matchyourgame.language.Language;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.sql.Timestamp;

@AllArgsConstructor @Data
public class UserObject {

    private int id;
    private String username;
    private long discordID;
    private Language language;
    private Timestamp createdAt;
    private Timestamp lastChangeAt;
    private long createGuild;
    private UserRole role;
    private boolean anonymized;
    private boolean active;

    public String getUsername() {
        return anonymized ? "Anonymisierter Benutzer" : username;
    }

}
