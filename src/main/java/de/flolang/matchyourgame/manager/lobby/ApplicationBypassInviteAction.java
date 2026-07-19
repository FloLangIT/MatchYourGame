package de.flolang.matchyourgame.manager.lobby;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Invite;
import net.dv8tion.jda.api.requests.Request;
import net.dv8tion.jda.api.requests.Response;
import net.dv8tion.jda.api.requests.Route;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.requests.restaction.AuditableRestActionImpl;
import okhttp3.RequestBody;

/**
 * JDA 6.4.1 does not expose Discord's IS_APPLICATION_BYPASS invite flag yet.
 * Keep the unsupported API detail isolated here so this class can be removed once JDA adds a public setter.
 */
final class ApplicationBypassInviteAction extends AuditableRestActionImpl<Invite> {
    private static final int APPLICATION_BYPASS_FLAG = 1 << 3;
    private final int maxAgeSeconds;
    private final int maxUses;

    ApplicationBypassInviteAction(JDA api, long channelId, int maxAgeSeconds, int maxUses) {
        super(api, Route.Invites.CREATE_INVITE.compile(Long.toUnsignedString(channelId)));
        this.maxAgeSeconds = maxAgeSeconds;
        this.maxUses = maxUses;
    }

    @Override
    protected RequestBody finalizeData() {
        return getRequestBody(DataObject.empty()
                .put("max_age", maxAgeSeconds)
                .put("max_uses", maxUses)
                .put("unique", true)
                .put("flags", APPLICATION_BYPASS_FLAG));
    }

    @Override
    protected void handleSuccess(Response response, Request<Invite> request) {
        request.onSuccess(api.getEntityBuilder().createInvite(response.getObject()));
    }
}
