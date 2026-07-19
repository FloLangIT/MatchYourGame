package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.config.ConfigManager;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.Language;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.AdminAccess;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

public final class ProjectInviteListener extends ListenerAdapter {
    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id.equals("projectInvite")) {
            if (!canInvite(event.getUser().getIdLong())) return;
            UserObject leader = UserRepository.get(event.getUser().getIdLong());
            if (leader == null) return;
            int userId = leader.getId();
            event.replyModal(Modal.create("projectInviteTarget", t(userId, "ProjectInvite.Modal.Title"))
                    .addComponents(Label.of(t(userId, "ProjectInvite.Modal.Username"),
                            TextInput.create("username", TextInputStyle.SHORT).setRequired(true)
                                    .setMinLength(2).setMaxLength(32).build()),
                            Label.of(t(userId, "ProjectInvite.Modal.Language"),
                                    StringSelectMenu.create("language")
                                            .addOption("Deutsch", "DE", Emoji.fromFormatted("🇩🇪"))
                                            .addOption("English", "EN", Emoji.fromFormatted("🇺🇸"))
                                            .setRequiredRange(1, 1).build())).build()).queue();
        } else if (id.startsWith("projectInviteDecline-")) {
            long targetId = suffix(id);
            if (event.getUser().getIdLong() != targetId) return;
            Language language = languageFrom(id);
            event.replyModal(Modal.create("projectInviteDeclineReason-" + language.name() + "-" + targetId,
                            LanguageManager.getMessageByLanguage("ProjectInvite.Decline.Modal.Title", language))
                    .addComponents(Label.of(LanguageManager.getMessageByLanguage(
                                    "ProjectInvite.Decline.Modal.Reason", language),
                            TextInput.create("reason", TextInputStyle.PARAGRAPH).setRequired(true)
                                    .setMinLength(1).setMaxLength(1000).build())).build()).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (id.equals("projectInviteTarget")) {
            if (!canInvite(event.getUser().getIdLong())) return;
            String username = event.getValue("username").getAsString().trim().replaceFirst("^@", "");
            Language language;
            try { language = Language.valueOf(event.getValue("language").getAsStringList().getFirst()); }
            catch (IllegalArgumentException | NullPointerException exception) { return; }
            event.deferEdit().queue();
            findAndInvite(username, language);
        } else if (id.startsWith("projectInviteDeclineReason-")) {
            long targetId = suffix(id);
            if (event.getUser().getIdLong() != targetId) return;
            Language language = languageFrom(id);
            String reason = event.getValue("reason").getAsString().trim();
            event.editMessageEmbeds(new EmbedCreator().setDescription(
                            LanguageManager.getMessageByLanguage("ProjectInvite.Decline.Confirmed", language)).build())
                    .setComponents().queue(hook -> hook.deleteOriginal().queueAfter(5, TimeUnit.SECONDS));
            notifyProjectLeader(event.getUser().getName(), event.getUser().getId(), reason);
        }
    }

    private static void findAndInvite(String username, Language language) {
        List<Guild> guilds = Main.jda.getGuilds();
        if (guilds.isEmpty()) return;
        AtomicBoolean finished = new AtomicBoolean();
        AtomicInteger remaining = new AtomicInteger(guilds.size());
        for (Guild guild : guilds) guild.retrieveMembersByPrefix(username, 100)
                .setTimeout(Duration.ofSeconds(15))
                .onSuccess(members -> {
                    if (!finished.get()) members.stream()
                            .filter(member -> member.getUser().getName().equalsIgnoreCase(username))
                            .findFirst().ifPresent(member -> {
                                if (finished.compareAndSet(false, true)) invite(member, language);
                            });
                    remaining.decrementAndGet();
                }).onError(ignored -> remaining.decrementAndGet());
    }

    private static void invite(Member member, Language language) {
        if (UserRepository.get(member.getIdLong()) != null) return;
        member.getUser().openPrivateChannel().queue(dm -> dm.sendMessageEmbeds(new EmbedCreator()
                        .setTitle(LanguageManager.getMessageByLanguage("ProjectInvite.Message.Title", language))
                        .setDescription(LanguageManager.getMessageByLanguage("ProjectInvite.Message.Description", language)).build())
                .setComponents(ActionRow.of(
                        Button.success("createAccount-projectInvite-" + member.getIdLong(),
                                LanguageManager.getMessageByLanguage("ProjectInvite.Message.Accept", language)),
                        Button.danger("projectInviteDecline-" + language.name() + "-" + member.getIdLong(),
                                LanguageManager.getMessageByLanguage("ProjectInvite.Message.Decline", language))))
                .queue());
    }

    private static void notifyProjectLeader(String username, String userId, String reason) {
        String configuredId = ConfigManager.getString("Discord.ProjectLeaderID", "");
        if (configuredId.isBlank()) return;
        Main.jda.retrieveUserById(configuredId).queue(leader -> leader.openPrivateChannel().queue(dm ->
                dm.sendMessageEmbeds(new EmbedCreator().setTitle(
                                LanguageManager.getMessageByLanguage("ProjectInvite.Decline.Notification.Title", Language.DE))
                        .setDescription(localized("ProjectInvite.Decline.Notification.Description",
                                Language.DE, Map.of("%username%", username, "%discordId%", userId, "%reason%", reason))).build())
                        .setComponents(ActionRow.of(Button.danger("delete",
                                LanguageManager.getMessageByLanguage("General.Button.DeleteMessage", Language.DE))))
                        .queue()));
    }

    private static boolean isProjectLeader(long discordId) {
        return String.valueOf(discordId).equals(ConfigManager.getString("Discord.ProjectLeaderID", ""));
    }

    private static boolean canInvite(long discordId) {
        return AdminAccess.canInviteUsers(UserRepository.get(discordId));
    }

    private static long suffix(String id) {
        try { return Long.parseLong(id.substring(id.lastIndexOf('-') + 1)); }
        catch (NumberFormatException exception) { return 0; }
    }

    private static Language languageFrom(String id) {
        try { return Language.valueOf(id.split("-")[1]); }
        catch (IllegalArgumentException | ArrayIndexOutOfBoundsException exception) { return Language.EN; }
    }

    private static String localized(String key, Language language, Map<String, String> replacements) {
        String message = LanguageManager.getMessageByLanguage(key, language);
        for (Map.Entry<String, String> replacement : replacements.entrySet())
            message = message.replace(replacement.getKey(), replacement.getValue());
        return message;
    }

    private static String t(int userId, String key) { return LanguageManager.getMessageForUser(key, userId); }
}
