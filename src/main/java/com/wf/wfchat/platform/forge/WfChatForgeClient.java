package com.wf.wfchat.platform.forge;

import com.wf.wfchat.WfChat;
import com.wf.wfchat.client.GlyphResources;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = WfChat.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
final class WfChatForgeClient {
    private WfChatForgeClient() {
    }

    @SubscribeEvent
    static void reloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new GlyphResources());
    }
}
