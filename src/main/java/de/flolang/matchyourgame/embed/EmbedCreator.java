package de.flolang.matchyourgame.embed;

import de.flolang.matchyourgame.config.ConfigManager;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.*;

public class EmbedCreator {

    private final EmbedBuilder eb;

    public EmbedCreator() {
        eb = new EmbedBuilder();
        eb.setColor(Color.getColor(ConfigManager.getString("Discord.DefaultEmbed.Color").replace("#", "0x")));
        eb.setFooter(ConfigManager.getString("Discord.DefaultEmbed.Footer"));
    }

    public EmbedCreator setTitle(String title) {
        eb.setTitle(title);
        return this;
    }

    public EmbedCreator setDescription(String description) {
        eb.setDescription(description);
        return this;
    }

    public EmbedCreator setColor(Color color) {
        eb.setColor(color);
        return this;
    }

    public EmbedCreator setFooter(String footer) {
        eb.setFooter(footer);
        return this;
    }

    public EmbedCreator setThumbnail(String url) {
        eb.setThumbnail(url);
        return this;
    }

    public EmbedCreator setImage(String url) {
        eb.setImage(url);
        return this;
    }

    public EmbedCreator addField(String name, String value, boolean inline) {
        eb.addField(name, value, inline);
        return this;
    }

    public EmbedCreator setAuthor(String name, String url, String iconUrl) {
        eb.setAuthor(name, url, iconUrl);
        return this;
    }

    public MessageEmbed build() {
        return eb.build();
    }

}
