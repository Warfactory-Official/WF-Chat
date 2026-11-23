package com.wf.wfchat.emoji;

import com.mojang.blaze3d.platform.NativeImage;

/** ABGR pixel access across NativeImage API generations. */
final class Pixels {
    private Pixels() {
    }

    static int get(NativeImage image, int x, int y) {
        //? if <26.1 {
        return image.getPixelRGBA(x, y);
        //?} else {
        /*return image.getPixel(x, y);
        *///?}
    }

    static void set(NativeImage image, int x, int y, int abgr) {
        //? if <26.1 {
        image.setPixelRGBA(x, y, abgr);
        //?} else {
        /*image.setPixel(x, y, abgr);
        *///?}
    }
}
