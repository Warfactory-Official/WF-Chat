package com.wf.wfchat.client;

import net.minecraft.network.chat.Component;

public final class Message {
    public final Component content;
    public final int tick;

    public Message(Component content, int tick) {
        this.content = content;
        this.tick = tick;
    }
}
