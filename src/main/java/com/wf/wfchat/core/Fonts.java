package com.wf.wfchat.core;

import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

public final class Fonts {
    private Fonts() {
    }

    public static Style apply(Style style, String font) {
        //? if <1.21 {
        /*return style.withFont(new ResourceLocation(font));
        *///?} else if <26.1 {
        return style.withFont(ResourceLocation.parse(font));
        //?} else {
        /*return style.withFont(new net.minecraft.network.chat.FontDescription.Resource(ResourceLocation.parse(font)));
        *///?}
    }
}
