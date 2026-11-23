package com.wf.wfchat.core;

import com.wf.wfchat.api.ChannelCommand;
import com.wf.wfchat.api.ChannelSpec;
import com.wf.wfchat.api.IncomingMessage;
import com.wf.wfchat.api.Sender;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/** {@code /msg <player>} (and aliases) opens a conversation channel with that player. */
public final class MsgChannelCommand implements ChannelCommand {
    private static final List<String> NAMES = List.of("msg", "tell", "w", "whisper");

    @Override
    public List<String> names() {
        return NAMES;
    }

    @Override
    public Optional<ChannelSpec> onTyped(String name, List<String> args) {
        if (args.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(spec(args.get(0)));
    }

    @Override
    public Optional<ChannelSpec> onIncoming(IncomingMessage message) {
        String type = message.chatType();
        if (type == null) {
            return Optional.empty();
        }
        String peer;
        if (type.endsWith(":msg_command_incoming")) {
            peer = message.senderName();
        } else if (type.endsWith(":msg_command_outgoing")) {
            peer = message.targetName();
        } else {
            return Optional.empty();
        }
        return peer == null || peer.isEmpty() ? Optional.empty() : Optional.of(spec(peer));
    }

    private static ChannelSpec spec(String player) {
        return ChannelSpec.builder("msg:" + player.toLowerCase(Locale.ROOT))
                .title(Component.literal("@" + player))
                .color(0xFF55FF)
                .order(100)
                .priority(1)
                .dynamic(true)
                .sender(Sender.command("msg " + player))
                .build();
    }
}
