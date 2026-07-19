package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.database.user.InboxMessageRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.ManagementMessageUpdater;
import de.flolang.matchyourgame.manager.UserControlManager;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class InboxListener extends ListenerAdapter {
    private static final int PAGE_SIZE = 23;

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.equals("inbox") && !id.startsWith("inboxPage-") && !id.startsWith("inboxOpen-")
                && !id.startsWith("inboxMarkRead-") && !id.startsWith("inboxDelete-")) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) { event.reply("Unauthorized").setEphemeral(true).queue(); return; }
        if (id.equals("inbox") || id.startsWith("inboxPage-")) {
            int page = id.equals("inbox") ? 0 : suffixInt(id);
            event.deferEdit().queue(); showInbox(event.getMessage(), user, page); return;
        }
        if (id.startsWith("inboxMarkRead-")) {
            long messageId = suffixLong(id);
            boolean read = InboxMessageRepository.markRead(messageId, user.getId());
            event.reply(t(user, read ? "Inbox.MarkedRead" : "Inbox.NotFound")).setEphemeral(true).queue();
            if (read) {
                ManagementMessageUpdater.refreshMainPage(user.getId());
                event.getMessage().editMessageComponents(ActionRow.of(
                        Button.danger("delete", t(user, "General.Button.DeleteMessage")))).queue();
            }
            return;
        }
        Parsed parsed = parse(id);
        if (parsed == null) { event.reply(t(user, "Inbox.NotFound")).setEphemeral(true).queue(); return; }
        if (id.startsWith("inboxDelete-")) {
            boolean deleted = InboxMessageRepository.delete(parsed.messageId(), user.getId());
            event.reply(t(user, deleted ? "Inbox.Deleted" : "Inbox.NotFound")).setEphemeral(true).queue();
            if (deleted) {
                ManagementMessageUpdater.refreshMainPage(user.getId());
                showInbox(event.getMessage(), user, parsed.page());
            }
        } else {
            event.deferEdit().queue(); showMessage(event.getMessage(), user, parsed.messageId(), parsed.page());
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().startsWith("inboxSelect-") || event.getValues().isEmpty()) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        long messageId;
        try { messageId = Long.parseLong(event.getValues().getFirst()); }
        catch (NumberFormatException e) { messageId = 0; }
        if (user == null || InboxMessageRepository.get(messageId, user.getId()) == null) {
            event.reply(user == null ? "Unauthorized" : t(user, "Inbox.NotFound")).setEphemeral(true).queue(); return;
        }
        event.deferEdit().queue(); showMessage(event.getMessage(), user, messageId, suffixInt(event.getComponentId()));
    }

    private static void showInbox(Message message, UserObject user, int requestedPage) {
        int count = InboxMessageRepository.count(user.getId(), false);
        int unread = InboxMessageRepository.count(user.getId(), true);
        int pages = Math.max(1, (count + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        List<InboxMessageRepository.InboxMessage> messages = InboxMessageRepository.getForUser(
                user.getId(), page * PAGE_SIZE, PAGE_SIZE);
        String entries = messages.stream().map(entry -> t(user, "Inbox.Entry", Map.of(
                        "%status%", t(user, entry.readAt() == null ? "Inbox.UnreadMarker" : "Inbox.ReadMarker"),
                        "%title%", shorten(entry.title(), 80), "%date%", discordTime(entry.createdAt()))))
                .reduce((a, b) -> a + "\n" + b).orElse(t(user, "Inbox.Empty"));
        List<ActionRow> rows = new ArrayList<>();
        if (!messages.isEmpty()) rows.add(ActionRow.of(StringSelectMenu.create("inboxSelect-" + page)
                .setPlaceholder(t(user, "Inbox.Select"))
                .addOptions(messages.stream().map(entry -> SelectOption.of(shorten(entry.title(), 100),
                                String.valueOf(entry.id())).withDescription(entry.readAt() == null
                                ? t(user, "Inbox.Unread") : t(user, "Inbox.Read"))).toList()).build()));
        if (pages > 1) rows.add(ActionRow.of(
                Button.secondary("inboxPage-" + Math.max(0, page - 1), t(user, "General.Previous")).withDisabled(page == 0),
                Button.secondary("inboxPage-" + Math.min(pages - 1, page + 1), t(user, "General.Next")).withDisabled(page + 1 >= pages)));
        rows.add(ActionRow.of(Button.primary("mainPage", t(user, "UserProfile.Button.Back"))));
        message.editMessageEmbeds(new EmbedCreator().setTitle(t(user, "Inbox.Title"))
                        .setDescription(t(user, "Inbox.Description", Map.of("%count%", String.valueOf(count),
                                "%unread%", String.valueOf(unread), "%page%", String.valueOf(page + 1),
                                "%pages%", String.valueOf(pages), "%messages%", entries))).build())
                .setComponents(rows).queue();
    }

    private static void showMessage(Message message, UserObject user, long messageId, int page) {
        InboxMessageRepository.InboxMessage inbox = InboxMessageRepository.get(messageId, user.getId());
        if (inbox == null) { showInbox(message, user, page); return; }
        if (inbox.readAt() == null) {
            InboxMessageRepository.markRead(messageId, user.getId());
            ManagementMessageUpdater.refreshMainPage(user.getId());
        }
        String description = inbox.content() + "\n\n" + t(user, "Inbox.SentAt", Map.of(
                "%date%", discordTime(inbox.createdAt())));
        message.editMessageEmbeds(new EmbedCreator().setTitle(inbox.title()).setDescription(description).build())
                .setComponents(ActionRow.of(
                        Button.danger("inboxDelete-" + messageId + "-" + page, t(user, "Inbox.Delete")),
                        Button.primary("inboxPage-" + page, t(user, "UserProfile.Button.Back")))).queue();
    }

    private static Parsed parse(String id) {
        String[] parts = id.split("-");
        if (parts.length < 3) return null;
        try { return new Parsed(Long.parseLong(parts[parts.length - 2]), Integer.parseInt(parts[parts.length - 1])); }
        catch (NumberFormatException e) { return null; }
    }

    private static int suffixInt(String id) { try { return Integer.parseInt(id.substring(id.lastIndexOf('-') + 1)); } catch (Exception e) { return 0; } }
    private static long suffixLong(String id) { try { return Long.parseLong(id.substring(id.lastIndexOf('-') + 1)); } catch (Exception e) { return 0; } }
    private static String discordTime(java.sql.Timestamp time) { return time == null ? "-" : "<t:" + time.getTime() / 1000 + ":F>"; }
    private static String shorten(String value, int max) { return value.length() <= max ? value : value.substring(0, max - 1) + "…"; }
    private static String t(UserObject user, String key) { return LanguageManager.getMessageByLanguage(key, user.getLanguage()); }
    private static String t(UserObject user, String key, Map<String, String> replacements) {
        String value = t(user, key); for (var entry : replacements.entrySet()) value = value.replace(entry.getKey(), entry.getValue()); return value;
    }
    private record Parsed(long messageId, int page) {}
}
