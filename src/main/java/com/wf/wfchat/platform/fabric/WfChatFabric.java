package com.wf.wfchat.platform.fabric;

import com.wf.wfchat.server.ServerChannels;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

public final class WfChatFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                ServerChannels.registerCommands(dispatcher));
    }
}
