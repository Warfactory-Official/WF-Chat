package com.wf.wfchat.emoji;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;

final class Textures {
    private Textures() {
    }

    static AbstractTexture create(NativeImage image) {
        //? if <26.1 {
        DynamicTexture t = new DynamicTexture(image);
        t.setFilter(true, false);
        return t;
        //?} else {
        /*return new DynamicTexture(() -> "wfchat emoji", image);
        *///?}
    }
}
