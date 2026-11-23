package com.wf.wfchat.platform.forge;

import com.wf.wfchat.WfChat;
import com.wf.wfchat.server.ServerChannels;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(WfChat.MOD_ID)
public final class WfChatForge {
    public WfChatForge() {
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> ServerChannels.registerCommands(e.getDispatcher()));
    }
}
