package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.lobby.ClanRepository;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.embed.EmbedCreator;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.UserControlManager;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class CrewMenuListener extends ListenerAdapter {
    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.equals("crews") && !id.startsWith("crew")) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        if (id.equals("crews") || id.startsWith("crewsPage-")) {
            int page = id.equals("crews") ? 0 : numberAfter(id, "crewsPage-");
            edit(event, user, manager -> manager.loadCrewsPage(page));
        } else if (id.equals("crewCreate")) {
            event.replyModal(nameModal("crewCreate", t(user, "UserProfile.Crews.Create.Title"),
                    t(user, "UserProfile.Crews.Create.Name"), null)).queue();
        } else if (id.startsWith("crewOpen-")) {
            int[] values = numbers(id, 2);
            edit(event, user, manager -> manager.loadCrewPage(values[0], values[1]));
        } else if (id.startsWith("crewInvite-")) {
            int[] values = numbers(id, 2);
            event.replyModal(nameModal("crewInvite-" + values[0] + "-" + values[1],
                    t(user, "UserProfile.Crews.Invite.Title"), t(user, "UserProfile.Crews.Invite.Username"), null)).queue();
        } else if (id.startsWith("crewRename-")) {
            int[] values = numbers(id, 2);
            ClanRepository.ClanInfo clan = ClanRepository.getForMember(values[0], user.getId());
            if (clan == null || !ClanRepository.canAdministrate(values[0], user.getId())) return;
            event.replyModal(nameModal("crewRename-" + values[0] + "-" + values[1],
                    t(user, "UserProfile.Crews.Rename.Title"), t(user, "UserProfile.Crews.Create.Name"), clan.name())).queue();
        } else if (id.startsWith("crewMembers-")) {
            int[] values = numbers(id, 3);
            edit(event, user, manager -> manager.loadCrewMembersPage(values[0], values[1], values[2]));
        } else if (id.startsWith("crewGames-")) {
            int[] values = numbers(id, 3);
            edit(event, user, manager -> manager.loadCrewGamesPage(values[0], values[1], values[2]));
        } else if (id.startsWith("crewKick-")) {
            int[] values = numbers(id, 4);
            boolean changed = ClanRepository.kick(values[0], user.getId(), values[1]);
            if (!changed) { event.reply(t(user, "UserProfile.Crews.ActionFailed")).setEphemeral(true).queue(); return; }
            edit(event, user, manager -> manager.loadCrewMembersPage(values[0], values[2], values[3]));
        } else if (id.startsWith("crewLeave-")) {
            int[] values = numbers(id, 2);
            boolean changed = ClanRepository.leave(values[0], user.getId());
            if (!changed) { event.reply(t(user, "UserProfile.Crews.ActionFailed")).setEphemeral(true).queue(); return; }
            edit(event, user, manager -> manager.loadCrewsPage(values[1]));
        } else if (id.startsWith("crewDeleteAsk-")) {
            int[] values = numbers(id, 2);
            if (ClanRepository.roleOf(values[0], user.getId()) != ClanRepository.Role.LEADER) return;
            event.deferEdit().queue();
            event.getMessage().editMessageEmbeds(new EmbedCreator()
                            .setTitle(t(user, "UserProfile.Crews.Delete.Title"))
                            .setDescription(t(user, "UserProfile.Crews.Delete.Description")).build())
                    .setComponents(ActionRow.of(
                            Button.danger("crewDeleteConfirm-" + values[0] + "-" + values[1],
                                    t(user, "UserProfile.Crews.Delete.Confirm")),
                            Button.secondary("crewOpen-" + values[0] + "-" + values[1], t(user, "UserProfile.Button.Back"))))
                    .queue();
        } else if (id.startsWith("crewDeleteConfirm-")) {
            int[] values = numbers(id, 2);
            boolean changed = ClanRepository.delete(values[0], user.getId());
            if (!changed) { event.reply(t(user, "UserProfile.Crews.ActionFailed")).setEphemeral(true).queue(); return; }
            edit(event, user, manager -> manager.loadCrewsPage(values[1]));
        } else if (id.startsWith("crewInviteAccept-")) {
            int invitationId = numbers(id, 1)[0];
            ClanRepository.Invitation invitation = ClanRepository.invitation(invitationId);
            boolean accepted = ClanRepository.respondToInvitation(invitationId, user.getId(), true);
            var confirmation = event.editMessageEmbeds(result(user, accepted ? "UserProfile.Crews.Invitation.Accepted"
                    : "UserProfile.Crews.Invitation.Unavailable")).setComponents();
            if (accepted && invitation != null) {
                notifyMemberJoined(invitation.clanId(), user);
                confirmation.queue(hook -> hook.deleteOriginal().queueAfter(5, TimeUnit.SECONDS));
            } else confirmation.queue();
        } else if (id.startsWith("crewInviteDecline-")) {
            int invitationId = numbers(id, 1)[0];
            boolean declined = ClanRepository.respondToInvitation(invitationId, user.getId(), false);
            event.editMessageEmbeds(result(user, declined ? "UserProfile.Crews.Invitation.Declined"
                    : "UserProfile.Crews.Invitation.Unavailable")).setComponents().queue();
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("crew") || event.getValues().isEmpty()) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        if (id.startsWith("crewSelect-")) {
            int page = numberAfter(id, "crewSelect-");
            int crewId = selected(event);
            edit(event, user, manager -> manager.loadCrewPage(crewId, page));
        } else if (id.startsWith("crewMemberSelect-")) {
            int[] values = numbers(id, 3);
            int targetId = selected(event);
            edit(event, user, manager -> manager.loadCrewMemberPage(values[0], targetId, values[1], values[2]));
        } else if (id.startsWith("crewRole-")) {
            int[] values = numbers(id, 4);
            ClanRepository.Role role;
            try { role = ClanRepository.Role.valueOf(event.getValues().getFirst()); }
            catch (IllegalArgumentException exception) { return; }
            boolean changed = ClanRepository.changeRole(values[0], user.getId(), values[1], role);
            if (!changed) { event.reply(t(user, "UserProfile.Crews.ActionFailed")).setEphemeral(true).queue(); return; }
            edit(event, user, manager -> manager.loadCrewMembersPage(values[0], values[2], values[3]));
        } else if (id.startsWith("crewGameToggle-")) {
            int[] values = numbers(id, 3);
            boolean changed = ClanRepository.toggleGame(values[0], user.getId(), selected(event));
            if (!changed) { event.reply(t(user, "UserProfile.Crews.ActionFailed")).setEphemeral(true).queue(); return; }
            edit(event, user, manager -> manager.loadCrewGamesPage(values[0], values[1], values[2]));
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (!id.startsWith("crew")) return;
        UserObject user = UserController.get(event.getUser().getIdLong());
        if (user == null) return;
        if (id.equals("crewCreate")) {
            ClanRepository.ClanInfo clan = ClanRepository.create(value(event, "value"), user.getId());
            if (clan == null) { event.reply(t(user, "UserProfile.Crews.Create.Failed")).setEphemeral(true).queue(); return; }
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadCrewPage(clan.id(), 0);
        } else if (id.startsWith("crewRename-")) {
            int[] values = numbers(id, 2);
            boolean changed = ClanRepository.rename(values[0], user.getId(), value(event, "value"));
            if (!changed) { event.reply(t(user, "UserProfile.Crews.Rename.Failed")).setEphemeral(true).queue(); return; }
            event.deferEdit().queue();
            new UserControlManager(event.getMessage(), user).loadCrewPage(values[0], values[1]);
        } else if (id.startsWith("crewInvite-")) {
            int[] values = numbers(id, 2);
            UserObject invitee = UserController.get(value(event, "value"));
            ClanRepository.Invitation invitation = invitee == null ? null
                    : ClanRepository.invite(values[0], user.getId(), invitee.getId());
            ClanRepository.ClanInfo clan = ClanRepository.getForMember(values[0], user.getId());
            if (invitation == null || clan == null || !sendInvitation(user, invitee, clan, invitation)) {
                event.reply(t(user, "UserProfile.Crews.Invite.Failed")).setEphemeral(true).queue(); return;
            }
            event.reply(t(user, "UserProfile.Crews.Invite.Sent")).setEphemeral(true).queue();
        }
    }

    private static boolean sendInvitation(UserObject inviter, UserObject invitee, ClanRepository.ClanInfo clan,
                                          ClanRepository.Invitation invitation) {
        if (Main.jda == null || invitee == null) return false;
        Main.jda.retrieveUserById(invitee.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
                dm.sendMessageEmbeds(new EmbedCreator().setTitle(t(invitee, "UserProfile.Crews.Invitation.Title"))
                                .setDescription(t(invitee, "UserProfile.Crews.Invitation.Description", Map.of(
                                        "%inviter%", inviter.getUsername(), "%clan%", clan.name()))).build())
                        .setComponents(ActionRow.of(
                                Button.success("crewInviteAccept-" + invitation.id(), t(invitee, "UserProfile.Crews.Invitation.Accept")),
                                Button.danger("crewInviteDecline-" + invitation.id(), t(invitee, "UserProfile.Crews.Invitation.Decline"))))
                        .queue()));
        return true;
    }

    private static void notifyMemberJoined(int clanId, UserObject joinedUser) {
        if (Main.jda == null) return;
        ClanRepository.ClanInfo clan = ClanRepository.getForMember(clanId, joinedUser.getId());
        if (clan == null) return;
        for (int memberId : ClanRepository.members(clanId)) {
            if (memberId == joinedUser.getId()) continue;
            UserObject member = UserController.get(memberId);
            if (member == null) continue;
            Main.jda.retrieveUserById(member.getDiscordID()).queue(discordUser -> discordUser.openPrivateChannel().queue(dm ->
                    dm.sendMessageEmbeds(new EmbedCreator()
                                    .setTitle(t(member, "UserProfile.Crews.MemberJoined.Title"))
                                    .setDescription(t(member, "UserProfile.Crews.MemberJoined.Description", Map.of(
                                            "%user%", joinedUser.getUsername(), "%clan%", clan.name()))).build())
                            .setComponents(ActionRow.of(Button.danger("delete",
                                    t(member, "General.Button.DeleteMessage"))))
                            .queue()));
        }
    }

    private static Modal nameModal(String id, String title, String label, String value) {
        TextInput.Builder input = TextInput.create("value", TextInputStyle.SHORT).setRequired(true).setMinLength(1).setMaxLength(60);
        if (value != null) input.setValue(value);
        return Modal.create(id, title).addComponents(Label.of(label, input.build())).build();
    }

    private static void edit(ButtonInteractionEvent event, UserObject user,
                             java.util.function.Consumer<UserControlManager> action) {
        event.deferEdit().queue(); action.accept(new UserControlManager(event.getMessage(), user));
    }

    private static void edit(StringSelectInteractionEvent event, UserObject user,
                             java.util.function.Consumer<UserControlManager> action) {
        event.deferEdit().queue(); action.accept(new UserControlManager(event.getMessage(), user));
    }

    private static net.dv8tion.jda.api.entities.MessageEmbed result(UserObject user, String key) {
        return new EmbedCreator().setDescription(t(user, key)).build();
    }

    private static int selected(StringSelectInteractionEvent event) {
        try { return Integer.parseInt(event.getValues().getFirst()); } catch (NumberFormatException exception) { return -1; }
    }

    private static int[] numbers(String id, int count) {
        String[] parts = id.split("-");
        int[] result = new int[count];
        for (int i = 0; i < count; i++) try { result[i] = Integer.parseInt(parts[parts.length - count + i]); }
        catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) { result[i] = 0; }
        return result;
    }

    private static int numberAfter(String value, String prefix) {
        try { return Math.max(0, Integer.parseInt(value.substring(prefix.length()))); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String value(ModalInteractionEvent event, String id) { return event.getValue(id).getAsString().trim(); }
    private static String t(UserObject user, String key) { return LanguageManager.getMessageForUser(key, user.getId()); }
    private static String t(UserObject user, String key, Map<String, String> values) {
        return LanguageManager.getMessageForUser(key, user.getId(), values);
    }
}
