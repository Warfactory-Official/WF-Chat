package com.wf.wfchat.api;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** A message as the client received it, before routing. */
public final class IncomingMessage {
    public enum Kind {
        /** Signed or unsigned player chat. */
        PLAYER,
        /** Chat-typed message with no verifiable sender (commands such as /msg, /me, /say). */
        DISGUISED,
        /** Server system message: command feedback, join/leave, advancements. */
        SYSTEM,
        /** Produced by this client (mods, local commands). */
        CLIENT
    }

    private final Kind kind;
    private final Component content;
    private final @Nullable String chatType;
    private final @Nullable UUID senderId;
    private final @Nullable String senderName;
    private final @Nullable String targetName;
    private final @Nullable String body;
    private final @Nullable String stampedChannel;

    public IncomingMessage(Kind kind, Component content, @Nullable String chatType, @Nullable UUID senderId,
                           @Nullable String senderName, @Nullable String targetName, @Nullable String body,
                           @Nullable String stampedChannel) {
        this.kind = kind;
        this.content = content;
        this.chatType = chatType;
        this.senderId = senderId;
        this.senderName = senderName;
        this.targetName = targetName;
        this.body = body;
        this.stampedChannel = stampedChannel;
    }

    public Kind kind() {
        return kind;
    }

    /** The fully decorated line vanilla would have displayed. */
    public Component content() {
        return content;
    }

    /** Chat type registry key, e.g. {@code minecraft:msg_command_incoming}; null for system messages. */
    public @Nullable String chatType() {
        return chatType;
    }

    public @Nullable UUID senderId() {
        return senderId;
    }

    /** Plain name of the sender (profile name for PLAYER, bound name text for DISGUISED). */
    public @Nullable String senderName() {
        return senderName;
    }

    /** Plain name of the recipient for two-party chat types such as {@code msg_command_outgoing}. */
    public @Nullable String targetName() {
        return targetName;
    }

    /** Undecorated text the sender typed, when known. */
    public @Nullable String body() {
        return body;
    }

    /** Channel id the server stamped on this message (WF Chat server), or null. */
    public @Nullable String stampedChannel() {
        return stampedChannel;
    }
}
