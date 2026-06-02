package de.flolang.matchyourgame.database.game;

import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor @Data
public class GameObject {

    private int id;
    private int subGameFrom;
    private String name;
    private boolean skillbased;
    private boolean active;

    public GameObject getSubGameFrom() {
        if(subGameFrom == 0) return null;
        else return GameController.get(subGameFrom);
    }

}
