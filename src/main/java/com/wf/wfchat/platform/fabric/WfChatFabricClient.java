package com.wf.wfchat.platform.fabric;

import com.wf.wfchat.WfChat;
import com.wf.wfchat.client.GlyphResources;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;

public final class WfChatFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new Glyphs());
    }

    private static final class Glyphs extends GlyphResources implements IdentifiableResourceReloadListener {
        @Override
        public ResourceLocation getFabricId() {
            //? if <1.21 {
            /*return new ResourceLocation(WfChat.MOD_ID, "glyphs");
            *///?} else {
            return ResourceLocation.fromNamespaceAndPath(WfChat.MOD_ID, "glyphs");
            //?}
        }
    }
}
