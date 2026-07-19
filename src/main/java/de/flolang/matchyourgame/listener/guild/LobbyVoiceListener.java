package de.flolang.matchyourgame.listener.guild;

import de.flolang.matchyourgame.Main;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public final class LobbyVoiceListener extends ListenerAdapter {
    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        if (event.getChannelJoined() != null && Main.lobbyService != null)
            Main.lobbyService.markVoiceJoined(event.getChannelJoined().getIdLong(), event.getMember().getIdLong());
        if (event.getChannelLeft() != null && Main.lobbyService != null) {
            boolean noPlayersRemain = event.getChannelLeft().getMembers().stream()
                    .filter(member -> member.getIdLong() != event.getMember().getIdLong())
                    .noneMatch(member -> !member.getUser().isBot());
            if (noPlayersRemain) {
                int lobbyId = Main.lobbyService.closeEmptyVoiceChannel(event.getChannelLeft().getIdLong());
                if (lobbyId != 0 && Main.reviewService != null) Main.reviewService.assignAfterLobby(lobbyId);
                else if (lobbyId == 0)
                    Main.lobbyService.markVoiceLeft(event.getChannelLeft().getIdLong(), event.getMember().getIdLong());
            } else {
                Main.lobbyService.markVoiceLeft(event.getChannelLeft().getIdLong(), event.getMember().getIdLong());
            }
        }
    }
}
