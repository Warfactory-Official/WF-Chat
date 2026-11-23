package com.wf.wfchat.api;

/** Collects the channels a message belongs to. */
public interface ChannelSink {
    /** Adds the message to a registered channel. Unknown ids are ignored. */
    void add(String channelId);

    /** Registers the channel if absent, then adds the message to it. */
    void add(ChannelSpec spec);

    /** Marks the message as handled: built-in system/global/local classification is skipped. */
    void claim();

    boolean claimed();

    /** True when the sender is this client's player, or an entity inside the client's loaded chunks. */
    boolean senderIsNearby(IncomingMessage message);
}
