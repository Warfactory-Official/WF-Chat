package com.wf.wfchat.api;

import java.util.List;
import java.util.Optional;

/**
 * A command that opens a channel: typing {@code /msg bob hi} creates the channel {@code msg:bob};
 * replies from the server are routed into it. Plain text typed into that channel is wrapped by the
 * channel's {@link Sender}.
 */
public interface ChannelCommand {
    /** Command literals without the slash, aliases included. */
    List<String> names();

    /** The player typed {@code /<name> <args...>}. Return the channel to open or reuse. */
    Optional<ChannelSpec> onTyped(String name, List<String> args);

    /** An incoming message may belong to a channel this command owns; return it to open or reuse it. */
    Optional<ChannelSpec> onIncoming(IncomingMessage message);
}
