package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.friend.FriendRepository;
import de.flolang.matchyourgame.database.game.GameObject;
import de.flolang.matchyourgame.database.game.GameRepository;
import de.flolang.matchyourgame.database.lobby.LobbyRepository;
import de.flolang.matchyourgame.database.lobby.PassiveQueueSettings;
import de.flolang.matchyourgame.database.lobby.PassiveQueueSettingsRepository;
import de.flolang.matchyourgame.database.lobby.SearchProfileRepository;
import de.flolang.matchyourgame.database.party.PartyObject;
import de.flolang.matchyourgame.database.party.PartyRepository;
import de.flolang.matchyourgame.database.report.BanRepository;
import de.flolang.matchyourgame.database.report.WarningRepository;
import de.flolang.matchyourgame.database.review.ReviewRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.database.user.UserRole;
import de.flolang.matchyourgame.database.user.InboxMessageRepository;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.ManagementMessageUpdater;
import de.flolang.matchyourgame.manager.UserControlManager;
import de.flolang.matchyourgame.manager.AdminAccess;
import de.flolang.matchyourgame.manager.InboxService;
import de.flolang.matchyourgame.manager.review.RatingFormatter;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProjectUserAdminListener extends ListenerAdapter {
    private static final int PAGE_SIZE = 23;

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.equals("adminUsers") && !id.startsWith("adminUsersPage-")
                && !id.startsWith("adminUserOpen-") && !id.startsWith("adminUserDm-")
                && !id.startsWith("adminUserBan-") && !id.startsWith("adminUserWarn-")
                && !id.startsWith("adminUserUnban-") && !id.startsWith("adminUserReviews-")) return;
        UserObject admin = admin(event.getUser().getIdLong());
        if (admin == null) { event.reply("Unauthorized").setEphemeral(true).queue(); return; }
        if (id.equals("adminUsers") || id.startsWith("adminUsersPage-")) {
            int page = id.equals("adminUsers") ? 0 : suffix(id);
            event.deferEdit().queue(); showUsers(event.getMessage(), admin, page);
        } else if (id.startsWith("adminUserOpen-")) {
            int[] values = numbers(id, 2);
            event.deferEdit().queue(); showUser(event.getMessage(), admin, values[0], values[1]);
        } else if (id.startsWith("adminUserDm-")) {
            int[] values = numbers(id, 2);
            event.replyModal(Modal.create("adminUserDmSubmit-" + values[0] + "-" + values[1],
                            t(admin, "Admin.Users.Dm.Modal.Title"))
                    .addComponents(
                            Label.of(t(admin, "Admin.Users.Dm.Modal.EmbedTitle"),
                                    TextInput.create("title", TextInputStyle.SHORT).setRequired(true).setMaxLength(100).build()),
                            Label.of(t(admin, "Admin.Users.Dm.Modal.Message"),
                                    TextInput.create("message", TextInputStyle.PARAGRAPH).setRequired(true).setMaxLength(3000).build()),
                            Label.of(t(admin, "Admin.Users.Dm.Modal.Delivery"),
                                    StringSelectMenu.create("delivery")
                                            .addOption(t(admin, "Admin.Delivery.SILENT"), InboxMessageRepository.DeliveryMode.SILENT.name())
                                            .addOption(t(admin, "Admin.Delivery.DIRECT_DM"), InboxMessageRepository.DeliveryMode.DIRECT_DM.name())
                                            .setRequiredRange(1, 1).build()))
                    .build()).queue();
        } else if (id.startsWith("adminUserBan-")) {
            int[] values = numbers(id, 2);
            event.replyModal(Modal.create("adminUserBanSubmit-" + values[0] + "-" + values[1],
                            t(admin, "Admin.Users.Ban.Modal.Title"))
                    .addComponents(
                            Label.of(t(admin, "Admin.Users.Ban.Modal.Duration"),
                                    TextInput.create("duration", TextInputStyle.SHORT).setRequired(true).setMaxLength(20).build()),
                            Label.of(t(admin, "Admin.Users.Ban.Modal.Reason"),
                                    TextInput.create("reason", TextInputStyle.PARAGRAPH).setRequired(true).setMaxLength(1500).build()))
                    .build()).queue();
        } else if (id.startsWith("adminUserWarn-")) {
            int[] values = numbers(id, 2);
            event.replyModal(Modal.create("adminUserWarnSubmit-" + values[0] + "-" + values[1],
                            t(admin, "Admin.Users.Warning.Modal.Title"))
                    .addComponents(Label.of(t(admin, "Admin.Users.Warning.Modal.Reason"),
                            TextInput.create("reason", TextInputStyle.PARAGRAPH).setRequired(true).setMaxLength(1500).build()))
                    .build()).queue();
        } else if (id.startsWith("adminUserUnban-")) {
            int[] values = numbers(id, 2);
            UserObject target = UserRepository.get(values[0]);
            boolean success = target != null && mayManage(admin, target) && BanRepository.unban(target.getId());
            event.reply(t(admin, success ? "Admin.Users.Unban.Success" : "Admin.Users.Unban.Failed"))
                    .setEphemeral(true).queue();
            if (success) showUser(event.getMessage(), admin, target.getId(), values[1]);
        } else {
            int[] values = numbers(id, 3);
            event.deferEdit().queue(); showReviews(event.getMessage(), admin, values[0], values[1], values[2]);
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if ((!event.getComponentId().startsWith("adminUserSelect-")
                && !event.getComponentId().startsWith("adminUserRole-")) || event.getValues().isEmpty()) return;
        UserObject admin = admin(event.getUser().getIdLong());
        if (admin == null) { event.reply("Unauthorized").setEphemeral(true).queue(); return; }
        if (event.getComponentId().startsWith("adminUserRole-")) {
            int[] values = numbers(event.getComponentId(), 2);
            UserObject target = UserRepository.get(values[0]);
            UserRole role;
            try { role = UserRole.valueOf(event.getValues().getFirst()); } catch (IllegalArgumentException e) { return; }
            boolean allowed = target != null && target.getDiscordID() != admin.getDiscordID()
                    && !AdminAccess.isProjectLeader(target.getDiscordID())
                    && ((role == UserRole.ADMIN && AdminAccess.canAssignAdmins(admin))
                    || (role != UserRole.ADMIN && AdminAccess.canAssignModerators(admin)))
                    && UserRepository.setRole(target.getId(), role);
            event.reply(t(admin, allowed ? "Admin.Users.Role.Success" : "Admin.Users.Role.Failed"))
                    .setEphemeral(true).queue();
            if (allowed) {
                showUser(event.getMessage(), admin, target.getId(), values[1]);
                ManagementMessageUpdater.refreshMainPage(target.getId());
            }
            return;
        }
        int page = suffix(event.getComponentId());
        int userId;
        try { userId = Integer.parseInt(event.getValues().getFirst()); }
        catch (NumberFormatException exception) { return; }
        event.deferEdit().queue(); showUser(event.getMessage(), admin, userId, page);
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (!id.startsWith("adminUserDmSubmit-") && !id.startsWith("adminUserBanSubmit-")
                && !id.startsWith("adminUserWarnSubmit-")) return;
        UserObject admin = admin(event.getUser().getIdLong());
        if (admin == null) { event.reply("Unauthorized").setEphemeral(true).queue(); return; }
        int[] values = numbers(id, 2);
        UserObject target = UserRepository.get(values[0]);
        if (target == null) { event.reply(t(admin, "Admin.Users.NotFound")).setEphemeral(true).queue(); return; }
        if (id.startsWith("adminUserDmSubmit-")) {
            String title = value(event, "title"), message = value(event, "message");
            InboxMessageRepository.DeliveryMode delivery;
            try { delivery = InboxMessageRepository.DeliveryMode.valueOf(
                    event.getValue("delivery").getAsStringList().getFirst()); }
            catch (Exception e) { delivery = InboxMessageRepository.DeliveryMode.SILENT; }
            boolean sent = InboxService.sendToUser(admin, target, title, message, delivery) != null;
            event.reply(t(admin, sent ? "Admin.Users.Dm.Sent" : "Admin.Users.Dm.Failed",
                    Map.of("%delivery%", t(admin, "Admin.Delivery." + delivery.name())))).setEphemeral(true).queue();
            return;
        }
        if (id.startsWith("adminUserWarnSubmit-")) {
            String reason = value(event, "reason");
            WarningRepository.Warning warning = mayManage(admin, target)
                    ? WarningRepository.create(target.getId(), admin.getId(), reason) : null;
            event.reply(t(admin, warning != null ? "Admin.Users.Warning.Success" : "Admin.Users.Warning.Failed"))
                    .setEphemeral(true).queue();
            if (warning != null) notifyWarning(target, reason);
            return;
        }
        Instant expires;
        try { expires = expiry(value(event, "duration")); }
        catch (IllegalArgumentException exception) {
            event.reply(t(admin, "Admin.Users.Ban.InvalidDuration")).setEphemeral(true).queue(); return;
        }
        String reason = value(event, "reason");
        boolean banned = mayManage(admin, target)
                && BanRepository.ban(target.getId(), 0, expires, reason, admin.getDiscordID());
        event.reply(t(admin, banned ? "Admin.Users.Ban.Success" : "Admin.Users.Ban.Failed"))
                .setEphemeral(true).queue();
        if (banned) {
            removeFromActiveFeatures(target.getId());
            notifyBan(target, expires, reason);
        }
    }

    private static void showUsers(Message message, UserObject admin, int requestedPage) {
        List<UserObject> users = UserRepository.getAll();
        int pages = Math.max(1, (users.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int from = Math.min(page * PAGE_SIZE, users.size()), to = Math.min(from + PAGE_SIZE, users.size());
        String entries = users.subList(from, to).stream().map(user -> t(admin, "Admin.Users.Entry", Map.of(
                        "%username%", user.getUsername(), "%discord%", discordName(user), "%rating%", rating(user.getId()))))
                .reduce((a, b) -> a + "\n" + b).orElse(t(admin, "Admin.Users.None"));
        var embed = new EmbedCreator().setTitle(t(admin, "Admin.Users.Title"))
                .setDescription(t(admin, "Admin.Users.Description", Map.of("%count%", String.valueOf(users.size()),
                        "%page%", String.valueOf(page + 1), "%pages%", String.valueOf(pages), "%users%", entries))).build();
        List<ActionRow> rows = new ArrayList<>();
        if (from < to) rows.add(ActionRow.of(StringSelectMenu.create("adminUserSelect-" + page)
                .setPlaceholder(t(admin, "Admin.Users.Select"))
                .addOptions(users.subList(from, to).stream().map(user -> SelectOption.of(user.getUsername(),
                                String.valueOf(user.getId())).withDescription(shorten(discordName(user) + " · " + rating(user.getId()), 100)))
                        .toList()).build()));
        if (pages > 1) rows.add(ActionRow.of(
                Button.secondary("adminUsersPage-" + Math.max(0, page - 1), t(admin, "General.Previous")).withDisabled(page == 0),
                Button.secondary("adminUsersPage-" + Math.min(pages - 1, page + 1), t(admin, "General.Next"))
                        .withDisabled(page >= pages - 1)));
        rows.add(ActionRow.of(Button.primary("adminPanel", t(admin, "UserProfile.Button.Back"))));
        message.editMessageEmbeds(embed).setComponents(rows).queue();
    }

    private static void showUser(Message message, UserObject admin, int targetId, int returnPage) {
        UserObject target = UserRepository.get(targetId);
        if (target == null) { showUsers(message, admin, returnPage); return; }
        PassiveQueueSettings settings = PassiveQueueSettingsRepository.get(targetId);
        String passiveGames = SearchProfileRepository.getForUser(targetId).stream().filter(profile -> profile.passiveEnabled())
                .map(profile -> GameRepository.get(profile.gameId())).filter(java.util.Objects::nonNull).map(GameObject::getName)
                .distinct().reduce((a, b) -> a + ", " + b).orElse("-");
        String friends = FriendRepository.getAcceptedFriendIds(targetId).stream().map(UserRepository::get)
                .filter(java.util.Objects::nonNull).map(UserObject::getUsername)
                .reduce((a, b) -> a + ", " + b).orElse("-");
        friends = shorten(friends, 1200);
        PartyObject party = PartyRepository.getForUser(targetId);
        var lobby = LobbyRepository.getActiveForUser(targetId);
        BanRepository.BanInfo ban = BanRepository.getActive(targetId);
        java.util.HashMap<String, String> replacements = new java.util.HashMap<>();
        replacements.put("%username%", target.getUsername()); replacements.put("%userId%", String.valueOf(target.getId()));
        replacements.put("%discord%", discordName(target)); replacements.put("%discordId%", String.valueOf(target.getDiscordID()));
        replacements.put("%language%", target.getLanguage().name()); replacements.put("%createdAt%", discordTime(target.getCreatedAt()));
        replacements.put("%rating%", rating(targetId)); replacements.put("%passive%", onOff(admin, settings.enabled()));
        replacements.put("%sync%", onOff(admin, settings.syncOnlineStatus())); replacements.put("%passiveGames%", passiveGames);
        replacements.put("%party%", party == null ? "-" : "#" + party.id());
        replacements.put("%lobby%", lobby == null ? "-" : "#" + lobby.getId());
        replacements.put("%banned%", ban == null ? onOff(admin, false) : onOff(admin, true));
        replacements.put("%friends%", friends);
        List<WarningRepository.Warning> warnings = WarningRepository.forUser(targetId);
        replacements.put("%role%", AdminAccess.role(target).name());
        replacements.put("%warnings%", warnings.isEmpty() ? "-" : warnings.stream().map(w -> "<t:" + w.createdAt().getTime()/1000
                + ":d> · " + shorten(w.reason(), 180)).reduce((a,b)->a+"\n"+b).orElse("-"));
        boolean manageable = mayManage(admin, target);
        List<ActionRow> rows = new ArrayList<>();
        rows.add(ActionRow.of(Button.primary("adminUserDm-" + targetId + "-" + returnPage, t(admin, "Admin.Users.Dm.Button")),
                Button.secondary("adminUserReviews-" + targetId + "-" + returnPage + "-0", t(admin, "Admin.Users.Reviews.Button")),
                Button.danger("adminUserBan-" + targetId + "-" + returnPage, t(admin, "Admin.Users.Ban.Button"))
                        .withDisabled(ban != null || !manageable),
                Button.success("adminUserUnban-" + targetId + "-" + returnPage, t(admin, "Admin.Users.Unban.Button"))
                        .withDisabled(ban == null || !manageable),
                Button.secondary("adminUserWarn-" + targetId + "-" + returnPage, t(admin, "Admin.Users.Warning.Button"))
                        .withDisabled(!manageable)));
        if (AdminAccess.canAssignModerators(admin) && !AdminAccess.isProjectLeader(target.getDiscordID())) {
            List<SelectOption> roles = new ArrayList<>();
            roles.add(SelectOption.of("User", UserRole.USER.name()).withDefault(target.getRole()==UserRole.USER));
            roles.add(SelectOption.of("Moderator", UserRole.MODERATOR.name()).withDefault(target.getRole()==UserRole.MODERATOR));
            if (AdminAccess.canAssignAdmins(admin)) roles.add(SelectOption.of("Admin", UserRole.ADMIN.name()).withDefault(target.getRole()==UserRole.ADMIN));
            rows.add(ActionRow.of(StringSelectMenu.create("adminUserRole-"+targetId+"-"+returnPage)
                    .setPlaceholder(t(admin,"Admin.Users.Role.Select")).addOptions(roles).build()));
        }
        rows.add(ActionRow.of(Button.primary("adminUsersPage-" + returnPage, t(admin, "UserProfile.Button.Back"))));
        message.editMessageEmbeds(LanguageManager.getEmbedForUser("Admin.Users.Detail", admin.getId(), replacements).build())
                .setComponents(rows).queue();
    }

    private static void showReviews(Message message, UserObject admin, int targetId, int returnPage, int requestedPage) {
        UserObject target = UserRepository.get(targetId);
        if (target == null) { showUsers(message, admin, returnPage); return; }
        List<ReviewRepository.ReceivedReview> reviews = ReviewRepository.receivedBy(targetId);
        int pages=Math.max(1,(reviews.size()+9)/10), page=Math.max(0,Math.min(requestedPage,pages-1));
        int from=Math.min(page*10,reviews.size()),to=Math.min(from+10,reviews.size());
        String entries=reviews.subList(from,to).stream().map(r -> {
            String reviewer=r.reviewerUsername()==null?"#"+r.reviewerUserId():r.reviewerUsername();
            return t(admin,"Admin.Users.Reviews.Entry",Map.of("%id%",String.valueOf(r.assignmentId()),
                    "%reviewer%",reviewer,"%lobby%",String.valueOf(r.lobbyId()),"%behavior%",String.valueOf(r.behaviorStars()),
                    "%teamplay%",String.valueOf(r.teamplayStars()),"%reliability%",String.valueOf(r.reliabilityStars()),
                    "%moderation%",r.moderationStars()==null?"-":String.valueOf(r.moderationStars()),
                    "%feedback%",r.privateFeedback()==null||r.privateFeedback().isBlank()?"-":shorten(r.privateFeedback(),500)));
        }).reduce((a,b)->a+"\n\n"+b).orElse(t(admin,"Admin.Users.Reviews.None"));
        List<ActionRow> rows=new ArrayList<>();
        if(pages>1) rows.add(ActionRow.of(Button.secondary("adminUserReviews-"+targetId+"-"+returnPage+"-"+Math.max(0,page-1),t(admin,"General.Previous")).withDisabled(page==0),
                Button.secondary("adminUserReviews-"+targetId+"-"+returnPage+"-"+Math.min(pages-1,page+1),t(admin,"General.Next")).withDisabled(page>=pages-1)));
        rows.add(ActionRow.of(Button.primary("adminUserOpen-"+targetId+"-"+returnPage,t(admin,"UserProfile.Button.Back"))));
        message.editMessageEmbeds(new EmbedCreator().setTitle(t(admin,"Admin.Users.Reviews.Title",Map.of("%username%",target.getUsername())))
                .setDescription(t(admin,"Admin.Users.Reviews.Description",Map.of("%count%",String.valueOf(reviews.size()),"%page%",String.valueOf(page+1),
                        "%pages%",String.valueOf(pages),"%reviews%",entries))).build()).setComponents(rows).queue();
    }

    private static boolean mayManage(UserObject actor, UserObject target) {
        if (actor == null || target == null || actor.getId() == target.getId() || AdminAccess.isProjectLeader(target.getDiscordID())) return false;
        if (AdminAccess.role(actor) == UserRole.ADMIN) return true;
        return AdminAccess.role(actor) == UserRole.MODERATOR && AdminAccess.role(target) == UserRole.USER;
    }

    private static void removeFromActiveFeatures(int userId) {
        PartyObject party = PartyRepository.getForUser(userId);
        if (party != null && PartyRepository.leaveAndTransferHost(party.id(), userId))
            ManagementMessageUpdater.refreshPartyState(party.memberIds());
        if (Main.lobbyService != null) Main.lobbyService.excludeBannedUser(userId);
    }

    private static void notifyBan(UserObject target, Instant expires, String reason) {
        String until = expires == null ? targetText(target, "Report.Ban.Forever") : "<t:" + expires.getEpochSecond() + ":F>";
        Main.jda.retrieveUserById(target.getDiscordID()).queue(user -> user.openPrivateChannel().queue(dm ->
                dm.sendMessageEmbeds(new EmbedCreator().setTitle(targetText(target, "Report.Ban.Title"))
                                .setDescription(targetText(target, "Report.Ban.Description", Map.of(
                                        "%until%", until, "%reason%", reason))).build())
                        .setComponents(ActionRow.of(Button.danger("delete",
                                targetText(target, "General.Button.DeleteMessage")))).queue()));
    }

    private static void notifyWarning(UserObject target, String reason) {
        Main.jda.retrieveUserById(target.getDiscordID()).queue(user -> user.openPrivateChannel().queue(dm ->
                dm.sendMessageEmbeds(new EmbedCreator().setTitle(targetText(target,"Admin.Users.Warning.Notification.Title"))
                                .setDescription(targetText(target,"Admin.Users.Warning.Notification.Description",Map.of("%reason%",reason))).build())
                        .setComponents(ActionRow.of(Button.danger("delete",targetText(target,"General.Button.DeleteMessage")))).queue()));
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

    private static UserObject admin(long discordId) {
        return AdminAccess.panelUser(discordId);
    }

    private static String discordName(UserObject user) {
        net.dv8tion.jda.api.entities.User discord = Main.jda == null ? null : Main.jda.getUserById(user.getDiscordID());
        return discord == null ? String.valueOf(user.getDiscordID()) : "@" + discord.getName();
    }

    private static String rating(int userId) {
        Double rating = ReviewRepository.averageRating(userId);
        return rating == null ? "-" : RatingFormatter.stars(rating);
    }

    private static String onOff(UserObject admin, boolean value) { return t(admin, value ? "General.On" : "General.Off"); }
    private static String discordTime(java.sql.Timestamp timestamp) { return "<t:" + timestamp.getTime() / 1000 + ":F>"; }
    private static String shorten(String text, int max) { return text.length() <= max ? text : text.substring(0, max - 1) + "…"; }
    private static int suffix(String id) { try { return Integer.parseInt(id.substring(id.lastIndexOf('-') + 1)); } catch (Exception e) { return 0; } }
    private static int[] numbers(String id, int count) {
        String[] parts = id.split("-"); int[] result = new int[count];
        for (int i = 0; i < count; i++) try { result[i] = Integer.parseInt(parts[parts.length - count + i]); }
        catch (Exception ignored) { result[i] = 0; }
        return result;
    }
    private static String value(ModalInteractionEvent event, String id) { return event.getValue(id).getAsString().trim(); }
    private static String t(UserObject user, String key) { return LanguageManager.getMessageForUser(key, user.getId()); }
    private static String t(UserObject user, String key, Map<String, String> values) {
        return LanguageManager.getMessageForUser(key, user.getId(), values);
    }
    private static String targetText(UserObject user, String key) {
        return LanguageManager.getMessageByLanguage(key, user.getLanguage());
    }
    private static String targetText(UserObject user, String key, Map<String, String> values) {
        String message = targetText(user, key);
        for (Map.Entry<String, String> value : values.entrySet()) message = message.replace(value.getKey(), value.getValue());
        return message;
    }
}
