package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.config.ConfigManager;
import de.flolang.matchyourgame.database.report.ReportContactRepository;
import de.flolang.matchyourgame.database.report.ReportRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.UserControlManager;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

import java.util.Map;

public final class ReportListener extends ListenerAdapter {
    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        switch (event.getComponentId()) {
            case "reportMenu" -> {
                event.deferEdit().queue();
                new UserControlManager(event.getMessage(), user).loadReportPage();
            }
            case "reportPlayer" -> event.replyModal(Modal.create("reportSubmit-player", t(user, "Report.Player.Title"))
                    .addComponents(
                            field(user, "Report.Player.Username", "username", TextInputStyle.SHORT, 100),
                            field(user, "Report.Player.Reason", "details", TextInputStyle.PARAGRAPH, 3000)).build()).queue();
            case "reportBug" -> event.replyModal(Modal.create("reportSubmit-bug", t(user, "Report.Bug.Title"))
                    .addComponents(
                            field(user, "Report.Bug.Subject", "subject", TextInputStyle.SHORT, 100),
                            field(user, "Report.Bug.Details", "details", TextInputStyle.PARAGRAPH, 3000)).build()).queue();
            case "reportFeedback" -> event.replyModal(Modal.create("reportSubmit-feedback", t(user, "Report.Feedback.Title"))
                    .addComponents(field(user, "Report.Feedback.Details", "details", TextInputStyle.PARAGRAPH, 3000))
                    .build()).queue();
            default -> { }
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!event.getModalId().startsWith("reportSubmit-")) return;
        UserObject reporter = UserController.get(event.getUser().getIdLong());
        if (reporter == null) return;
        String type = event.getModalId().substring("reportSubmit-".length());
        UserObject target = null;
        String subject;
        if (type.equals("player")) {
            target = UserController.get(value(event, "username"));
            if (target == null) {
                event.reply(t(reporter, "Report.Player.NotFound")).setEphemeral(true).queue();
                return;
            }
            if (!ReportContactRepository.hadContact(reporter.getId(), target.getId())) {
                event.reply(t(reporter, "Report.Player.NoContact")).setEphemeral(true).queue();
                return;
            }
            subject = target.getUsername();
        } else if (type.equals("bug")) {
            subject = value(event, "subject");
        } else if (type.equals("feedback")) {
            subject = t(reporter, "Report.Feedback.ChannelSubject");
        } else return;
        TextChannel channel = reportChannel();
        if (channel == null) {
            event.reply(t(reporter, "Report.Unavailable")).setEphemeral(true).queue();
            return;
        }
        String details = value(event, "details");
        ReportRepository.Report report = ReportRepository.create(type, reporter.getId(),
                target == null ? null : target.getId(), subject, details);
        if (report == null) {
            event.reply(t(reporter, "Report.Unavailable")).setEphemeral(true).queue();
            return;
        }
        EmbedCreator embed = new EmbedCreator()
                .setTitle(t(reporter, "Report.Channel.Title", Map.of("%type%", type.toUpperCase())) + " #" + report.id())
                .setDescription(details)
                .addField("Reporter", reporter.getUsername() + " (MYG #" + reporter.getId() +
                        ", Discord " + reporter.getDiscordID() + ")", false)
                .addField("Subject", subject, false);
        if (target != null) embed.addField("Target", target.getUsername() + " (MYG #" + target.getId() +
                ", Discord " + target.getDiscordID() + ")", false);
        java.util.List<ActionRow> controls = new java.util.ArrayList<>();
        if (type.equals("player")) controls.add(ActionRow.of(Button.danger("reportBan-" + report.id(),
                t(reporter, "Report.Channel.Ban"))));
        if (type.equals("bug")) controls.add(ActionRow.of(Button.primary("reportContact-" + report.id(),
                t(reporter, "Report.Channel.Contact"))));
        if (type.equals("feedback")) controls.add(ActionRow.of(Button.danger("reportDelete-" + report.id(),
                t(reporter, "Report.Channel.Delete"))));
        controls.add(ActionRow.of(
                Button.success("reportResolve-" + report.id(), t(reporter, "Report.Channel.Resolve")),
                Button.danger("reportReject-" + report.id(), t(reporter, "Report.Channel.Reject"))));
        event.deferReply(true).queue(hook -> channel.sendMessageEmbeds(embed.build()).setComponents(controls).queue(
                sent -> {
                    ReportRepository.setMessageId(report.id(), sent.getIdLong());
                    hook.editOriginal(t(reporter, "Report.Sent")).queue();
                },
                error -> hook.editOriginal(t(reporter, "Report.Unavailable")).queue()));
    }

    private static Label field(UserObject user, String key, String id, TextInputStyle style, int maxLength) {
        return Label.of(t(user, key), TextInput.create(id, style).setRequired(true).setMaxLength(maxLength).build());
    }

    private static String value(ModalInteractionEvent event, String id) {
        return event.getValue(id) == null ? "" : event.getValue(id).getAsString().trim();
    }

    private static TextChannel reportChannel() {
        try {
            long guildId = Long.parseLong(ConfigManager.getString("Discord.MYGGuildID", "0"));
            long channelId = Long.parseLong(ConfigManager.getString("Discord.ReportChannelID", "0"));
            Guild guild = Main.jda == null ? null : Main.jda.getGuildById(guildId);
            return guild == null ? null : guild.getTextChannelById(channelId);
        } catch (NumberFormatException ignored) { return null; }
    }

    private static String t(UserObject user, String key) {
        return LanguageManager.getMessageForUser(key, user.getId());
    }

    private static String t(UserObject user, String key, Map<String, String> replacements) {
        return LanguageManager.getMessageForUser(key, user.getId(), replacements);
    }
}
