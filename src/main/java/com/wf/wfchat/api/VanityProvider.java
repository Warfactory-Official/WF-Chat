package com.wf.wfchat.api;

import java.util.UUID;
import net.minecraft.network.chat.Component;

/**
 * Supplies tags shown before a player's name. Text may contain glyph shortcodes
 * ({@code :namespace/name:}), expanded through {@link Glyphs}.
 */
@FunctionalInterface
public interface VanityProvider {
    void tags(UUID player, String name, TagSink sink);

    interface TagSink {
        void add(Component tag);

        /** Parses glyph shortcodes in {@code text} and adds the result. */
        void add(String text, int rgb);
    }
}
