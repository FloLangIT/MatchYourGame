package de.flolang.matchyourgame.database.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameStatDefinitionParserTest {
    @Test
    void parsesPlayerAndTeamStatistics() {
        List<GameStatDefinition> result = GameStatDefinitionParser.parse(
                "PLAYER:Kills:INTEGER TEAM:Team_Score:DECIMAL");

        assertEquals(2, result.size());
        assertEquals(GameStatDefinition.Scope.PLAYER, result.get(0).scope());
        assertEquals(GameStatDefinition.ValueType.INTEGER, result.get(0).valueType());
        assertEquals("Team Score", result.get(1).name());
        assertEquals(GameStatDefinition.Scope.TEAM, result.get(1).scope());
    }

    @Test
    void acceptsCommaSeparatedDefinitions() {
        assertEquals(2, GameStatDefinitionParser.parse(
                "PLAYER:Won:BOOLEAN,TEAM:Comment:TEXT").size());
    }

    @Test
    void rejectsInvalidDefinition() {
        assertThrows(IllegalArgumentException.class,
                () -> GameStatDefinitionParser.parse("PLAYER:Kills:NUMBER"));
    }
}
