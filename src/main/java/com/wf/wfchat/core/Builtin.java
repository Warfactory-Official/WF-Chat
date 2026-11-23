package com.wf.wfchat.core;

import com.wf.wfchat.api.ChannelSpec;
import com.wf.wfchat.api.Sender;
import net.minecraft.network.chat.Component;

public final class Builtin {
    public static final String SYSTEM = "system";
    public static final String GLOBAL = "global";
    public static final String LOCAL = "local";

    public static final ChannelSpec SYSTEM_SPEC = ChannelSpec.builder(SYSTEM)
            .title(Component.translatable("wfchat.channel.system")).color(0xFFAA00).order(10).priority(30)
            .sender(Sender.CHAT).build();
    public static final ChannelSpec GLOBAL_SPEC = ChannelSpec.builder(GLOBAL)
            .title(Component.translatable("wfchat.channel.global")).color(0xFFFFFF).order(20).priority(20)
            .sender(Sender.CHAT).build();
    public static final ChannelSpec LOCAL_SPEC = ChannelSpec.builder(LOCAL)
            .title(Component.translatable("wfchat.channel.local")).color(0x55FFFF).order(30).priority(10)
            .sender(Sender.ifServerMod(Sender.command("wfchat local"), Sender.CHAT)).build();

    private Builtin() {
    }

    public static void registerDefaults(Registry registry) {
        registry.channel(LOCAL_SPEC);
        registry.channel(GLOBAL_SPEC);
        registry.channel(SYSTEM_SPEC);
    }
}
