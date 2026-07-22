package de.flolang.matchyourgame.manager;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.user.InboxMessageRepository;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.logging.DiscordLogService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

import java.util.List;

public final class InboxService {
    private InboxService() {}

    public static InboxMessageRepository.InboxMessage sendToUser(UserObject sender, UserObject target,
                                                                  String title, String content,
                                                                  InboxMessageRepository.DeliveryMode mode) {
        if (sender == null || target == null) return null;
        InboxMessageRepository.InboxMessage message = InboxMessageRepository.create(
                target.getId(), sender.getId(), title, content, mode);
        if (message == null) return null;
        ManagementMessageUpdater.refreshMainPage(target.getId());
        if (mode == InboxMessageRepository.DeliveryMode.DIRECT_DM) deliver(message);
        return message;
    }

    public static InboxMessageRepository.InboxMessage sendLinkedToUser(UserObject sender, UserObject target,
                                                                        String title, String content,
                                                                        InboxMessageRepository.DeliveryMode mode,
                                                                        String referenceType, long referenceId) {
        if (sender == null || target == null) return null;
        InboxMessageRepository.InboxMessage message = InboxMessageRepository.create(
                target.getId(), sender.getId(), title, content, mode, referenceType, referenceId);
        if (message == null) return null;
        ManagementMessageUpdater.refreshMainPage(target.getId());
        if (mode == InboxMessageRepository.DeliveryMode.DIRECT_DM) deliver(message);
        return message;
    }

    public static List<InboxMessageRepository.InboxMessage> broadcast(UserObject sender, String title,
                                                                       String content,
                                                                       InboxMessageRepository.DeliveryMode mode) {
        if (sender == null) return List.of();
        List<InboxMessageRepository.InboxMessage> messages = InboxMessageRepository.createBroadcast(
                sender.getId(), title, content, mode);
        for (InboxMessageRepository.InboxMessage message : messages) {
            ManagementMessageUpdater.refreshMainPage(message.recipientUserId());
            if (mode == InboxMessageRepository.DeliveryMode.DIRECT_DM) deliver(message);
        }
        DiscordLogService.action("ADMIN_BROADCAST", "Admin " + sender.getUsername() + " (#" + sender.getId()
                + ") hat einen Broadcast an " + messages.size() + " Nutzer erstellt · Versand " + mode.name());
        return messages;
    }

    private static void deliver(InboxMessageRepository.InboxMessage message) {
        if (Main.jda == null) return;
        UserObject target = UserRepository.get(message.recipientUserId());
        if (target == null) return;
        Main.jda.retrieveUserById(target.getDiscordID()).queue(
                user -> user.openPrivateChannel().queue(
                        dm -> dm.sendMessageEmbeds(new EmbedCreator().setTitle(message.title())
                                        .setDescription(message.content()).build())
                                .setComponents(ActionRow.of(
                                        Button.success("inboxMarkRead-" + message.id(), text(target, "Inbox.MarkRead")),
                                        Button.danger("delete", text(target, "General.Button.DeleteMessage"))))
                                .queue(sent -> InboxMessageRepository.markDelivered(message.id(), target.getId()),
                                        error -> DiscordLogService.error("INBOX_DM", "Inbox-DM #" + message.id()
                                                + " konnte User #" + target.getId()
                                                + " nicht zugestellt werden", error.toString())),
                        error -> DiscordLogService.error("INBOX_DM", "DM-Channel für User #" + target.getId()
                                + " konnte nicht geöffnet werden", error.toString())),
                error -> DiscordLogService.error("INBOX_DM", "Discord-User für MYG #" + target.getId()
                        + " konnte nicht geladen werden", error.toString()));
    }

    private static String text(UserObject user, String key) {
        return LanguageManager.getMessageByLanguage(key, user.getLanguage());
    }
}
