package com.wf.wfchat.core;

import java.util.List;

/** Decoded animation: fully composited canvas-sized ARGB frames. */
public record Anim(int width, int height, List<int[]> argb, int[] delayMs) {

    /** Non-premultiplied source-over, integer approximation shared by APNG and WebP. */
    public static int over(int src, int dst) {
        int sa = src >>> 24;
        if (sa == 0) {
            return dst;
        }
        if (sa == 255) {
            return src;
        }
        int da = (dst >>> 24) * (256 - sa) >> 8;
        int ba = sa + da;
        int scale = (1 << 24) / ba;
        int r = channel(src >>> 16 & 0xFF, sa, dst >>> 16 & 0xFF, da, scale);
        int g = channel(src >>> 8 & 0xFF, sa, dst >>> 8 & 0xFF, da, scale);
        int b = channel(src & 0xFF, sa, dst & 0xFF, da, scale);
        return ba << 24 | r << 16 | g << 8 | b;
    }

    private static int channel(int s, int sa, int d, int da, int scale) {
        return (int) (((long) (s * sa + d * da) * scale & 0xFFFFFFFFL) >>> 24);
    }
}
