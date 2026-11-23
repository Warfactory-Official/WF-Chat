package com.wf.wfchat.platform.neoforge;

import com.wf.wfchat.WfChat;
import com.wf.wfchat.client.GlyphResources;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
//? if <26.1 {
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
//?} else {
/*import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
*///?}

//? if <26.1 {
@EventBusSubscriber(modid = WfChat.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
//?} else {
/*@EventBusSubscriber(modid = WfChat.MOD_ID, value = Dist.CLIENT)
*///?}
final class WfChatNeoForgeClient {
    private WfChatNeoForgeClient() {
    }

    //? if <26.1 {
    @SubscribeEvent
    static void reloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new GlyphResources());
    }
    //?} else {
    /*@SubscribeEvent
    static void reloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(net.minecraft.resources.Identifier.fromNamespaceAndPath(WfChat.MOD_ID, "glyphs"),
                new GlyphResources());
    }
    *///?}
}
