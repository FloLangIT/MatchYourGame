package de.flolang.matchyourgame.database.friend;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.sql.Timestamp;

@AllArgsConstructor @Data
public class FriendObject {

    private int requesterID;
    private int receiverID;
    private Timestamp sent_at;
    private Timestamp accepted_at;

}
