package de.flolang.matchyourgame.listener.user;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class DeleteMessageListener extends ListenerAdapter {

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if(event.getButton().getCustomId().equalsIgnoreCase("delete")) {
            event.deferEdit().queue();
            event.getMessage().delete().queue();
        }
    }
}
