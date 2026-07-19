package de.flolang.matchyourgame.listener.guild;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.config.ConfigManager;
import de.flolang.matchyourgame.database.report.BanRepository;
import de.flolang.matchyourgame.database.report.ReportRepository;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.database.party.PartyObject;
import de.flolang.matchyourgame.database.party.PartyRepository;
import de.flolang.matchyourgame.manager.ManagementMessageUpdater;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class ReportModerationListener extends ListenerAdapter {
    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id.startsWith("reportDmReply-") || id.startsWith("reportDmEnd-")) {
            handleDmControl(event, id);
            return;
        }
        if (!id.startsWith("reportBan-") && !id.startsWith("reportContact-")
                && !id.startsWith("reportResolve-") && !id.startsWith("reportReject-")
                && !id.startsWith("reportDelete-")) return;
        int reportId = suffix(id);
        ReportRepository.Report report = ReportRepository.get(reportId);
        if (report == null || report.status() != ReportRepository.Status.OPEN || !isReportChannel(event.getChannel().getIdLong())) {
            event.reply(tr(report, "Report.Moderation.NotAvailable")).setEphemeral(true).queue();
            return;
        }
        if (id.startsWith("reportDelete-")) {
            boolean deleted = report.type().equals("feedback") && ReportRepository.finish(report.id(),
                    ReportRepository.Status.DELETED, "Deleted by moderation", event.getUser().getIdLong());
            if (!deleted) {
                event.reply(tr(report, "Report.Moderation.NotAvailable")).setEphemeral(true).queue();
                return;
            }
            event.deferEdit().queue(ignored -> event.getMessage().delete().queue());
        } else if (id.startsWith("reportBan-")) {
            event.replyModal(Modal.create("reportBanSubmit-" + reportId, tr(report, "Report.Moderation.BanTitle"))
                    .addComponents(
                            Label.of(tr(report, "Report.Moderation.Duration"),
                                    TextInput.create("duration", TextInputStyle.SHORT).setRequired(true).build()),
                            Label.of(tr(report, "Report.Moderation.Reason"), TextInput.create("message", TextInputStyle.PARAGRAPH)
                                    .setRequired(true).setMaxLength(1500).build())).build()).queue();
        } else if (id.startsWith("reportContact-")) {
            openContactChannel(event, report);
        } else {
            boolean resolved = id.startsWith("reportResolve-");
            event.replyModal(Modal.create("reportFinishSubmit-" + reportId + "-" + (resolved ? "resolved" : "rejected"),
                            tr(report, resolved ? "Report.Moderation.ResolveTitle" : "Report.Moderation.RejectTitle"))
                    .addComponents(Label.of(tr(report, "Report.Moderation.UserMessage"),
                            TextInput.create("message", TextInputStyle.PARAGRAPH).setRequired(true).setMaxLength(1500).build()))
                    .build()).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (id.startsWith("reportDmReplySubmit-")) {
            submitDmReply(event, suffix(id));
            return;
        }
        if (id.startsWith("reportBanSubmit-")) {
            int reportId = suffix(id);
            ReportRepository.Report report = ReportRepository.get(reportId);
            if (report == null || report.status() != ReportRepository.Status.OPEN || report.targetUserId() == null) {
                event.reply(tr(report, "Report.Moderation.NotAvailable")).setEphemeral(true).queue(); return;
            }
            Instant expires;
            try { expires = expiry(value(event, "duration")); }
            catch (IllegalArgumentException exception) {
                event.reply(tr(report, "Report.Moderation.InvalidDuration")).setEphemeral(true).queue(); return;
            }
            String reason = value(event, "message");
            boolean banned = BanRepository.ban(report.targetUserId(), reportId, expires, reason, event.getUser().getIdLong());
            event.reply(tr(report, banned ? "Report.Moderation.BanSuccess" : "Report.Moderation.BanFailed"))
                    .setEphemeral(true).queue();
            if (banned) {
                removeFromActiveFeatures(report.targetUserId());
                notifyBan(report.targetUserId(), expires, reason);
            }
            return;
        }
        if (!id.startsWith("reportFinishSubmit-")) return;
        String[] parts = id.split("-");
        int reportId = Integer.parseInt(parts[1]);
        ReportRepository.Status status = parts[2].equals("resolved")
                ? ReportRepository.Status.RESOLVED : ReportRepository.Status.REJECTED;
        String message = value(event, "message");
        ReportRepository.Report report = ReportRepository.get(reportId);
        boolean changed = report != null && ReportRepository.finish(reportId, status, message, event.getUser().getIdLong());
        event.reply(tr(report, changed ? "Report.Moderation.Updated" : "Report.Moderation.NotAvailable"))
                .setEphemeral(true).queue();
        if (changed) {
            clearReportControls(event, report);
            notifyFinished(report, status, message);
            closeContactBridge(report, status);
        }
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        if (event.isFromGuild()) {
            ReportRepository.Report report = ReportRepository.getOpenByContactChannel(event.getChannel().getIdLong());
            if (report != null) relayToUser(report, event.getMessage());
        }
    }

    private static void openContactChannel(ButtonInteractionEvent event, ReportRepository.Report report) {
        if (!report.type().equals("bug")) { event.reply(tr(report, "Report.Moderation.ContactBugOnly")).setEphemeral(true).queue(); return; }
        if (report.contactChannelId() != 0) {
            event.reply(tr(report, "Report.Moderation.ContactExists") + " <#" + report.contactChannelId() + ">").setEphemeral(true).queue(); return;
        }
        TextChannel reports = configuredReportChannel();
        Category category = reports == null ? null : reports.getParentCategory();
        if (category == null) { event.reply(tr(report, "Report.Moderation.CategoryMissing")).setEphemeral(true).queue(); return; }
        UserObject reporter = UserRepository.get(report.reporterId());
        String username = reporter == null ? "user" : reporter.getUsername().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        event.deferReply(true).queue(hook -> category.createTextChannel("report-" + report.id() + "-" + username).queue(channel -> {
            ReportRepository.setContactChannel(report.id(), channel.getIdLong());
            channel.sendMessage(tr(report, "Report.Moderation.ContactIntro", java.util.Map.of(
                    "%reportId%", String.valueOf(report.id())))).queue();
            notifyContactOpened(report, reporter);
            hook.editOriginal(tr(report, "Report.Moderation.ContactCreated") + " " + channel.getAsMention()).queue();
        }, error -> hook.editOriginal(tr(report, "Report.Moderation.ContactFailed")).queue()));
    }

    private static void relayToUser(ReportRepository.Report report, Message message) {
        UserObject reporter = UserRepository.get(report.reporterId());
        if (reporter == null) return;
        String line = "\n\n**" + t(reporter, "Report.Contact.TeamLabel") + " – " +
                message.getAuthor().getName() + ":**\n" + content(message);
        if (ReportRepository.appendContactLine(report.id(), line)) updateConversationMessage(ReportRepository.get(report.id()));
    }

    private static void handleDmControl(ButtonInteractionEvent event, String id) {
        ReportRepository.Report report = ReportRepository.get(suffix(id));
        UserObject user = UserRepository.get(event.getUser().getIdLong());
        if (report == null || user == null || report.reporterId() != user.getId()
                || report.status() != ReportRepository.Status.OPEN || report.contactClosed()) {
            event.reply(user == null ? "Unavailable" : t(user, "Report.Contact.Unavailable"))
                    .setEphemeral(true).queue(); return;
        }
        if (id.startsWith("reportDmReply-")) {
            event.replyModal(Modal.create("reportDmReplySubmit-" + report.id(), t(user, "Report.Contact.ReplyTitle"))
                    .addComponents(Label.of(t(user, "Report.Contact.ReplyMessage"),
                            TextInput.create("message", TextInputStyle.PARAGRAPH)
                                    .setRequired(true).setMaxLength(3000).build())).build()).queue();
        } else {
            boolean closed = ReportRepository.closeContact(report.id());
            if (!closed) { event.reply(t(user, "Report.Contact.Unavailable")).setEphemeral(true).queue(); return; }
            event.deferEdit().queue(ignored -> event.getMessage().delete().queue());
            TextChannel channel = Main.jda.getTextChannelById(report.contactChannelId());
            if (channel != null) channel.sendMessage(tr(report, "Report.Contact.EndedForTeam")).queue();
        }
    }

    private static void submitDmReply(ModalInteractionEvent event, int reportId) {
        ReportRepository.Report report = ReportRepository.get(reportId);
        UserObject user = UserRepository.get(event.getUser().getIdLong());
        if (report == null || user == null || report.reporterId() != user.getId()
                || report.status() != ReportRepository.Status.OPEN || report.contactClosed()) {
            event.reply(user == null ? "Unavailable" : t(user, "Report.Contact.Unavailable"))
                    .setEphemeral(true).queue(); return;
        }
        String reply = value(event, "message");
        String line = "\n\n**" + t(user, "Report.Contact.UserLabel") + ":**\n" + reply;
        if (!ReportRepository.appendContactLine(reportId, line)) {
            event.reply(t(user, "Report.Contact.Unavailable")).setEphemeral(true).queue(); return;
        }
        TextChannel channel = Main.jda.getTextChannelById(report.contactChannelId());
        if (channel != null) channel.sendMessage("**" + user.getUsername() + ":**\n" + reply).queue();
        updateConversationMessage(ReportRepository.get(reportId));
        event.reply(t(user, "Report.Contact.ReplySent")).setEphemeral(true).queue();
    }

    private static void updateConversationMessage(ReportRepository.Report report) {
        if (report == null || report.contactClosed()) return;
        UserObject reporter = UserRepository.get(report.reporterId());
        if (reporter == null) return;
        Main.jda.retrieveUserById(reporter.getDiscordID()).queue(user -> user.openPrivateChannel().queue(dm -> {
            if (report.contactDmMessageId() == 0) { createConversationMessage(dm, report, reporter); return; }
            dm.retrieveMessageById(report.contactDmMessageId()).queue(message -> message.editMessageEmbeds(
                            conversationEmbed(report, reporter)).setComponents(conversationControls(report, reporter)).queue(),
                    error -> createConversationMessage(dm, report, reporter));
        }));
    }

    private static void createConversationMessage(net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel dm,
                                                  ReportRepository.Report report, UserObject reporter) {
        dm.sendMessageEmbeds(conversationEmbed(report, reporter)).setComponents(conversationControls(report, reporter))
                .queue(message -> ReportRepository.setContactDmMessage(report.id(), message.getIdLong(),
                        report.contactTranscript()));
    }

    private static net.dv8tion.jda.api.entities.MessageEmbed conversationEmbed(ReportRepository.Report report,
                                                                                UserObject reporter) {
        String transcript = report.contactTranscript() == null ? t(reporter, "Report.Contact.OpenedDescription")
                : report.contactTranscript();
        if (transcript.length() > 3900) transcript = "…" + transcript.substring(transcript.length() - 3899);
        return new EmbedCreator().setTitle(t(reporter, "Report.Contact.OpenedTitle"))
                .setDescription(transcript).build();
    }

    private static ActionRow conversationControls(ReportRepository.Report report, UserObject reporter) {
        return ActionRow.of(
                Button.primary("reportDmReply-" + report.id(), t(reporter, "Report.Contact.Reply")),
                Button.danger("reportDmEnd-" + report.id(), t(reporter, "Report.Contact.End")));
    }

    private static void notifyContactOpened(ReportRepository.Report report, UserObject reporter) {
        if (reporter == null) return;
        Main.jda.retrieveUserById(reporter.getDiscordID()).queue(user -> user.openPrivateChannel().queue(dm ->
                dm.sendMessageEmbeds(new EmbedCreator().setTitle(t(reporter, "Report.Contact.OpenedTitle"))
                                .setDescription(t(reporter, "Report.Contact.OpenedDescription")).build())
                        .setComponents(ActionRow.of(
                                Button.primary("reportDmReply-" + report.id(), t(reporter, "Report.Contact.Reply")),
                                Button.danger("reportDmEnd-" + report.id(), t(reporter, "Report.Contact.End"))))
                        .queue(message -> ReportRepository.setContactDmMessage(report.id(), message.getIdLong(),
                                t(reporter, "Report.Contact.OpenedDescription")))));
    }

    private static void notifyFinished(ReportRepository.Report report, ReportRepository.Status status, String message) {
        UserObject reporter = UserRepository.get(report.reporterId());
        if (reporter == null) return;
        Main.jda.retrieveUserById(reporter.getDiscordID()).queue(user -> user.openPrivateChannel().queue(dm ->
                dm.sendMessageEmbeds(new EmbedCreator().setTitle(t(reporter, status == ReportRepository.Status.RESOLVED
                                ? "Report.Finished.ResolvedTitle" : "Report.Finished.RejectedTitle"))
                        .setDescription(message).build())
                        .setComponents(ActionRow.of(Button.danger("delete",
                                t(reporter, "General.Button.DeleteMessage")))).queue()));
    }

    private static void notifyBan(int targetId, Instant expires, String reason) {
        UserObject target = UserRepository.get(targetId);
        if (target == null) return;
        String until = expires == null ? t(target, "Report.Ban.Forever") : "<t:" + expires.getEpochSecond() + ":F>";
        Main.jda.retrieveUserById(target.getDiscordID()).queue(user -> user.openPrivateChannel().queue(dm ->
                dm.sendMessageEmbeds(new EmbedCreator().setTitle(t(target, "Report.Ban.Title"))
                        .setDescription(t(target, "Report.Ban.Description", java.util.Map.of(
                                "%until%", until, "%reason%", reason))).build()).queue()));
    }

    private static void removeFromActiveFeatures(int userId) {
        PartyObject party = PartyRepository.getForUser(userId);
        if (party != null && PartyRepository.leaveAndTransferHost(party.id(), userId))
            ManagementMessageUpdater.refreshPartyState(party.memberIds());
        if (Main.lobbyService != null) Main.lobbyService.excludeBannedUser(userId);
    }

    private static void clearReportControls(ModalInteractionEvent event, ReportRepository.Report report) {
        TextChannel channel = configuredReportChannel();
        if (channel != null && report.messageId() != 0) channel.retrieveMessageById(report.messageId()).queue(
                message -> message.editMessageComponents(java.util.List.of()).queue());
    }

    private static void closeContactBridge(ReportRepository.Report report, ReportRepository.Status status) {
        if (report.contactChannelId() == 0) return;
        ReportRepository.closeContact(report.id());
        UserObject reporter = UserRepository.get(report.reporterId());
        if (reporter != null && report.contactDmMessageId() != 0)
            Main.jda.retrieveUserById(reporter.getDiscordID()).queue(user -> user.openPrivateChannel().queue(dm ->
                    dm.deleteMessageById(report.contactDmMessageId()).queue(null, error -> {})));
        TextChannel channel = Main.jda.getTextChannelById(report.contactChannelId());
        if (channel != null) channel.sendMessage("This report was marked **" + status.name() + "**. The DM bridge is now closed.").queue();
    }

    private static Instant expiry(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("forever") || value.equals("permanent") || value.equals("dauerhaft")) return null;
        if (!value.matches("[1-9][0-9]*[mhdw]")) throw new IllegalArgumentException();
        long amount = Long.parseLong(value.substring(0, value.length() - 1));
        Duration duration = switch (value.charAt(value.length() - 1)) {
            case 'm' -> Duration.ofMinutes(amount); case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount); case 'w' -> Duration.ofDays(Math.multiplyExact(amount, 7));
            default -> throw new IllegalArgumentException();
        };
        return Instant.now().plus(duration);
    }

    private static String content(Message message) {
        String text = message.getContentDisplay().isBlank() ? "" : message.getContentDisplay();
        String attachments = message.getAttachments().stream().map(Message.Attachment::getUrl)
                .reduce((a, b) -> a + "\n" + b).orElse("");
        String result = (text + (text.isBlank() || attachments.isBlank() ? "" : "\n") + attachments).trim();
        return result.isBlank() ? "(empty message)" : result.substring(0, Math.min(result.length(), 4000));
    }

    private static boolean isReportChannel(long channelId) {
        TextChannel channel = configuredReportChannel(); return channel != null && channel.getIdLong() == channelId;
    }

    private static TextChannel configuredReportChannel() {
        try {
            long id = Long.parseLong(ConfigManager.getString("Discord.ReportChannelID", "0"));
            return Main.jda == null ? null : Main.jda.getTextChannelById(id);
        } catch (NumberFormatException ignored) { return null; }
    }

    private static int suffix(String id) { return Integer.parseInt(id.substring(id.lastIndexOf('-') + 1)); }
    private static String value(ModalInteractionEvent event, String id) {
        return event.getValue(id) == null ? "" : event.getValue(id).getAsString().trim();
    }
    private static String t(UserObject user, String key) { return LanguageManager.getMessageForUser(key, user.getId()); }
    private static String t(UserObject user, String key, java.util.Map<String, String> replacements) {
        return LanguageManager.getMessageForUser(key, user.getId(), replacements);
    }
    private static String tr(ReportRepository.Report report, String key) {
        UserObject user = report == null ? null : UserRepository.get(report.reporterId());
        return user == null ? LanguageManager.getMessageByLanguage(key, de.flolang.matchyourgame.language.Language.EN)
                : t(user, key);
    }
    private static String tr(ReportRepository.Report report, String key, java.util.Map<String, String> replacements) {
        String message = tr(report, key);
        for (var replacement : replacements.entrySet()) message = message.replace(replacement.getKey(), replacement.getValue());
        return message;
    }
}
