package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.manager.TutorialManager;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public final class TutorialInteractionListener extends ListenerAdapter {
    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id == null || !id.startsWith("tutorial")) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user != null) TutorialManager.handleButton(event, user);
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String id = event.getComponentId();
        if (id == null || !id.startsWith("tutorial")) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user != null) TutorialManager.handleSelection(event, user);
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!event.getModalId().startsWith("tutorial")) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user != null) TutorialManager.handleModal(event, user);
    }
}
