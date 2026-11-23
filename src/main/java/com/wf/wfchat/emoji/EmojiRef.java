package com.wf.wfchat.emoji;

import com.wf.wfchat.core.EmojiFile;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * One emoji image. {@link #ch} is the private-use placeholder drawn in the emoji font. Animated emoji are one
 * texture of {@link #frames} stacked squares.
 */
public final class EmojiRef {
    public static final int NONE = 0;
    public static final int LOADING = 1;
    public static final int READY = 2;
    public static final int FAILED = 3;

    public final char ch;
    public final String name;
    final String cacheKey;
    /** null => local cache file only (server emoji). */
    final @Nullable String url;
    final EmojiFile.Limits limits;
    volatile int state;
    volatile @Nullable ResourceLocation texture;
    volatile int frames = 1;
    volatile int @Nullable [] frameEndMs;

    EmojiRef(char ch, String name, String cacheKey, @Nullable String url, EmojiFile.Limits limits) {
        this.ch = ch;
        this.name = name;
        this.cacheKey = cacheKey;
        this.url = url;
        this.limits = limits;
    }

    public int state() {
        return state;
    }

    @Nullable
    public ResourceLocation texture() {
        return texture;
    }

    public int frames() {
        return frames;
    }

    /** Frame to show at {@code nowMs}; 0 when static. */
    public int frameAt(long nowMs) {
        int[] ends = frameEndMs;
        if (ends == null) {
            return 0;
        }
        long t = nowMs % ends[ends.length - 1];
        for (int i = 0; i < ends.length; i++) {
            if (t < ends[i]) {
                return i;
            }
        }
        return ends.length - 1;
    }
}
