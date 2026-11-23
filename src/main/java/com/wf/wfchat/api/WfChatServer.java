package com.wf.wfchat.api;

import com.wf.wfchat.core.Compat;
import com.wf.wfchat.core.Stamp;
import com.wf.wfchat.server.ServerChannels;
import java.util.Collection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/** Server-side half of the API. Safe to call whether or not the receiving clients run WF Chat. */
public final class WfChatServer {
    private WfChatServer() {
    }

    /** Who hears a message a player sends into a channel. */
    @FunctionalInterface
    public interface Recipients {
        Collection<ServerPlayer> of(ServerPlayer sender);
    }

    /**
     * Makes {@code /wfchat channel <id> <message>} deliver to {@code recipients}. Clients with a matching
     * {@link ChannelSpec} then send typed text there.
     */
    public static void channel(String id, Recipients recipients) {
        ServerChannels.register(id, recipients);
    }

    /** Tags an outgoing line so WF Chat clients file it under {@code channelId}; others see it unchanged. */
    public static Component stamp(String channelId, Component line) {
        return Stamp.write(channelId, null, null, line);
    }

    /** As {@link #stamp(String, Component)}, naming the author so clients can apply vanity tags. */
    public static Component stamp(String channelId, ServerPlayer author, Component line) {
        return Stamp.write(channelId, author.getUUID(), Compat.name(author.getGameProfile()), line);
    }

    /** Sends {@code line} stamped for {@code channelId} to each player. */
    public static void send(String channelId, Collection<ServerPlayer> players, @Nullable ServerPlayer author, Component line) {
        Component out = author != null ? stamp(channelId, author, line) : stamp(channelId, line);
        for (ServerPlayer p : players) {
            p.sendSystemMessage(out);
        }
    }
}
