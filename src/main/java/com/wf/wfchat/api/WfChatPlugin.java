package com.wf.wfchat.api;

/** Entry point for mods extending the chat. Register through {@link WfChatApi#register(WfChatPlugin)}. */
public interface WfChatPlugin {
    String id();

    void register(ChatRegistry registry);
}
