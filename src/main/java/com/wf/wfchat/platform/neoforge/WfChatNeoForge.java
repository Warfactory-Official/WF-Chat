package com.wf.wfchat.platform.neoforge;

import com.wf.wfchat.WfChat;
import com.wf.wfchat.server.ServerChannels;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod(WfChat.MOD_ID)
public final class WfChatNeoForge {
    public WfChatNeoForge(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> ServerChannels.registerCommands(e.getDispatcher()));
    }
}
