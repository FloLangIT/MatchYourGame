package de.flolang.matchyourgame.listener.user;

import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.database.guild.GuildObject;
import de.flolang.matchyourgame.database.guild.GuildRepository;
import de.flolang.matchyourgame.database.guild.PartnerGuildApplicationRepository;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.database.user.UserRepository;
import de.flolang.matchyourgame.language.LanguageManager;
import de.flolang.matchyourgame.manager.AdminAccess;
import de.flolang.matchyourgame.manager.PartnerGuildService;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

import java.util.Map;

public final class PartnerGuildProgramListener extends ListenerAdapter {
    @Override public void onButtonInteraction(ButtonInteractionEvent event) {
        String id=event.getComponentId();
        if(!id.startsWith("partnerProgram"))return;
        long guildId=suffix(id);
        if(id.startsWith("partnerProgramApply-")){
            UserObject manager=manager(event.getUser().getIdLong(),guildId);
            PartnerGuildApplicationRepository.Application application=PartnerGuildApplicationRepository.get(guildId);
            if(manager==null||application==null||(application.status()!=PartnerGuildApplicationRepository.Status.INVITED
                    && application.status()!=PartnerGuildApplicationRepository.Status.REJECTED
                    && application.status()!=PartnerGuildApplicationRepository.Status.WITHDRAWN)){
                event.reply(text(manager,"PartnerProgram.Invalid")).setEphemeral(true).queue();return;
            }
            event.replyModal(Modal.create("partnerProgramApplySubmit-"+guildId,text(manager,"PartnerProgram.Application.Title"))
                    .addComponents(Label.of(text(manager,"PartnerProgram.Application.Note"),
                            TextInput.create("note",TextInputStyle.PARAGRAPH).setRequired(false).setMaxLength(2000).build())).build()).queue();
        }else if(id.startsWith("partnerProgramApprove-")){
            UserObject admin=AdminAccess.panelUser(event.getUser().getIdLong());
            boolean success=PartnerGuildService.approve(admin,guildId);
            event.reply(text(admin,success?"PartnerProgram.Admin.Approved":"PartnerProgram.Invalid")).setEphemeral(true).queue();
            if(success&&event.getMessage()!=null)event.getMessage().delete().queue();
        }else if(id.startsWith("partnerProgramReject-")){
            UserObject admin=AdminAccess.panelUser(event.getUser().getIdLong());
            boolean success=PartnerGuildService.reject(admin,guildId);
            event.reply(text(admin,success?"PartnerProgram.Admin.Rejected":"PartnerProgram.Invalid")).setEphemeral(true).queue();
            if(success&&event.getMessage()!=null)event.getMessage().delete().queue();
        }else if(id.startsWith("partnerProgramConfirm-")){
            confirm(event,guildId);
        }
    }

    @Override public void onModalInteraction(ModalInteractionEvent event){
        if(!event.getModalId().startsWith("partnerProgramApplySubmit-"))return;
        long guildId=suffix(event.getModalId()); UserObject manager=manager(event.getUser().getIdLong(),guildId);
        if(manager==null){event.reply("Unauthorized").setEphemeral(true).queue();return;}
        String note=event.getValue("note")==null?"":event.getValue("note").getAsString().trim();
        boolean submitted=PartnerGuildApplicationRepository.submit(guildId,note);
        event.reply(text(manager,submitted?"PartnerProgram.Application.Sent":"PartnerProgram.Invalid")).setEphemeral(true).queue();
        if(submitted){PartnerGuildService.notifyAdminsOfApplication(guildId,note);if(event.getMessage()!=null&&!event.getMessage().isPinned())event.getMessage().delete().queue();}
    }

    private static void confirm(ButtonInteractionEvent event,long guildId){
        UserObject manager=manager(event.getUser().getIdLong(),guildId);
        GuildObject configured=GuildRepository.get(guildId);
        PartnerGuildApplicationRepository.Application application=PartnerGuildApplicationRepository.get(guildId);
        Guild guild=Main.jda==null?null:Main.jda.getGuildById(guildId);
        if(manager==null||configured==null||application==null
                ||application.status()!=PartnerGuildApplicationRepository.Status.ADMIN_APPROVED||guild==null){
            event.reply(text(manager,"PartnerProgram.Invalid")).setEphemeral(true).queue();return;
        }
        var missing=PartnerGuildService.missingPermissions(guild);
        if(!missing.isEmpty()){
            String permissions=PartnerGuildService.permissionNames(missing,manager.getLanguage());
            PartnerGuildService.notifyActivationProblem(configured,text(manager,"PartnerProgram.PermissionProblem",Map.of("%permissions%",permissions)));
            event.reply(text(manager,"PartnerProgram.Confirmation.MissingPermissions",Map.of("%permissions%",permissions)))
                    .setEphemeral(true).queue();return;
        }
        event.deferReply(true).queue(hook->{
            Category existing=configured.getMygVoiceCategoryId()>0?guild.getCategoryById(configured.getMygVoiceCategoryId()):null;
            if(existing!=null){
                var categoryMissing=PartnerGuildService.missingPermissions(guild,existing);
                if(!categoryMissing.isEmpty()){
                    String permissions=PartnerGuildService.permissionNames(categoryMissing,manager.getLanguage());
                    PartnerGuildService.notifyActivationProblem(configured,text(manager,"PartnerProgram.PermissionProblem",Map.of("%permissions%",permissions)));
                    hook.editOriginal(text(manager,"PartnerProgram.Confirmation.MissingPermissions",Map.of("%permissions%",permissions))).queue();
                    return;
                }
                activate(hook,event,manager,configured,existing);return;
            }
            guild.createCategory(LanguageManager.getMessageByLanguage("NewGuild.SetupGuild.VoiceCategory",configured.getLanguage()))
                    .queue(category->activate(hook,event,manager,configured,category),error->{
                        PartnerGuildService.notifyActivationProblem(configured,error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());
                        hook.editOriginal(text(manager,"PartnerProgram.Confirmation.Failed")).queue();
                    });
        });
    }

    private static void activate(net.dv8tion.jda.api.interactions.InteractionHook hook,ButtonInteractionEvent event,
                                 UserObject manager,GuildObject configured,Category category){
        boolean saved=GuildRepository.setPartnerGuild(configured.getGuildID(),true,category.getIdLong())
                && PartnerGuildApplicationRepository.activate(configured.getGuildID());
        hook.editOriginal(text(manager,saved?"PartnerProgram.Confirmation.Success":"PartnerProgram.Confirmation.Failed")).queue();
        if(saved&&event.getMessage()!=null&&!event.getMessage().isPinned())event.getMessage().delete().queue();
    }

    private static UserObject manager(long discordId,long guildId){GuildObject guild=GuildRepository.get(guildId);if(guild==null)return null;
        UserObject user=UserRepository.get(discordId);return user!=null&&user.getId()==guild.getManagerUserId()?user:null;}
    private static long suffix(String id){try{return Long.parseLong(id.substring(id.lastIndexOf('-')+1));}catch(Exception e){return 0;}}
    private static String text(UserObject user,String key){return user==null?key:LanguageManager.getMessageByLanguage(key,user.getLanguage());}
    private static String text(UserObject user,String key,Map<String,String> values){String value=text(user,key);for(var entry:values.entrySet())value=value.replace(entry.getKey(),entry.getValue());return value;}
}
