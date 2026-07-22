package de.flolang.matchyourgame.database.guild;

import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.language.Language;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.sql.Timestamp;

@AllArgsConstructor @Data
public class GuildObject {

    private final long guildID;
    private int managerUser;
    private long mygVoiceCategoryId;
    private long mygTextChannelId;
    private long mygTextMessageId;
    private boolean partnerGuild;
    private boolean active;
    private boolean partnerOperational;
    private boolean registrationOperational;
    private Language language;
    private final Timestamp addedAt;
    private Timestamp lastChangeAt;

    public UserObject getManagerUser() {
        return UserController.get(managerUser);
    }

    public int getManagerUserId() {
        return managerUser;
    }
}
