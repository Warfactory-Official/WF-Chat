package com.wf.wfchat.api;

import com.wf.wfchat.core.GlyphTable;
import net.minecraft.network.chat.Component;

/** Shortcode glyphs: {@code :name:} resolves to one character of a font definition. */
public final class Glyphs {
    private Glyphs() {
    }

    /** Expands every known {@code :shortcode:} in {@code text}; unknown ones stay literal. */
    public static Component expand(String text, int rgb) {
        return GlyphTable.expand(text, rgb);
    }

    public static boolean known(String name) {
        return GlyphTable.known(name);
    }
}
