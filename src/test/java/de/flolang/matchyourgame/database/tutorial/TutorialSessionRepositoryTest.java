package de.flolang.matchyourgame.database.tutorial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TutorialSessionRepositoryTest {
    @Test
    void sessionTransitionsPreserveIndependentTutorialData() {
        var original = new TutorialSessionRepository.Session(7,
                TutorialSessionRepository.Step.PROFILE_SETUP, List.of(11, 12), 0, 0, false, 0);

        var nextProfile = original.withProfileIndex(1);
        var lobby = nextProfile.withStep(TutorialSessionRepository.Step.LOBBY_ACTIVE).withLobbyGame(12);
        var match = lobby.withMatchAdded(true).withStep(TutorialSessionRepository.Step.PASSIVE_EXAMPLE);

        assertEquals(0, original.profileIndex());
        assertEquals(List.of(11, 12), match.modeIds());
        assertEquals(12, match.lobbyGameId());
        assertEquals(TutorialSessionRepository.Step.PASSIVE_EXAMPLE, match.step());
        assertFalse(original.matchAdded());
    }

    @Test
    void invitationPhasesKeepUsersOutUntilAcceptanceTransition() {
        var lobby = new TutorialSessionRepository.Session(9,
                TutorialSessionRepository.Step.LOBBY_ACTIVE, List.of(21), 1, 21, false, 2);

        var clanPending = lobby.withStep(TutorialSessionRepository.Step.CLAN_INVITES_PENDING);
        var clanAccepted = clanPending.withStep(TutorialSessionRepository.Step.CLAN_INVITED);
        var passivePending = clanAccepted.withStep(
                TutorialSessionRepository.Step.PASSIVE_INVITES_PENDING);

        assertEquals(TutorialSessionRepository.Step.CLAN_INVITES_PENDING, clanPending.step());
        assertEquals(TutorialSessionRepository.Step.CLAN_INVITED, clanAccepted.step());
        assertEquals(TutorialSessionRepository.Step.PASSIVE_INVITES_PENDING, passivePending.step());
        assertEquals(21, passivePending.lobbyGameId());
        assertEquals(2, passivePending.partySize());
    }

    @Test
    void partyMembershipControlsWhoStartsInTheLobby() {
        var party = new TutorialSessionRepository.Session(10,
                TutorialSessionRepository.Step.PARTY_CREATED, List.of(31), 1, 0, false, 1);

        var joined = party.withPartySize(2)
                .withStep(TutorialSessionRepository.Step.PARTY_ACTIVE);
        var kicked = joined.withPartySize(1)
                .withStep(TutorialSessionRepository.Step.PARTY_CREATED);
        var left = joined.withPartySize(0)
                .withStep(TutorialSessionRepository.Step.LOBBY_SELECTION);

        assertEquals(2, joined.partySize());
        assertEquals(1, kicked.partySize());
        assertEquals(0, left.partySize());
    }
}
