package de.flolang.matchyourgame.manager.review;

import de.flolang.matchyourgame.database.lobby.LobbyRepository;
import de.flolang.matchyourgame.database.review.ReviewAssignment;
import de.flolang.matchyourgame.database.review.ReviewRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

import java.util.List;

public final class ReviewService {
    private final JDA jda;
    public ReviewService(JDA jda) { this.jda = jda; }

    public void assignAfterLobby(int lobbyId) {
        List<ReviewAssignment> assignments = ReviewRepository.createRandomAssignments(lobbyId, LobbyRepository.memberIds(lobbyId));
        for (ReviewAssignment assignment : assignments) {
            UserObject reviewer = UserController.get(assignment.reviewerUserId());
            UserObject target = UserController.get(assignment.targetUserId());
            if (reviewer == null || target == null) continue;
            jda.retrieveUserById(reviewer.getDiscordID()).queue(user -> user.openPrivateChannel().queue(dm ->
                    dm.sendMessageEmbeds(new EmbedCreator().setTitle(LanguageManager.getMessageForUser("Review.Assignment.Title", reviewer.getId()))
                                    .setDescription(LanguageManager.getMessageForUser("Review.Assignment.Description", reviewer.getId(),
                                            java.util.Map.of("%target%", target.getUsername(), "%lobbyId%", String.valueOf(lobbyId)))).build())
                            .setComponents(ActionRow.of(
                                    Button.primary("reviewOpen-" + assignment.id(),
                                            LanguageManager.getMessageForUser("Review.Assignment.Button", reviewer.getId())))).queue()));
        }
    }
}
