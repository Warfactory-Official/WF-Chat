package com.wf.wfchat.core;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

public final class GlyphTable {
    private record Glyph(String font, char glyph) {
    }

    private static final Map<String, Glyph> TABLE = new HashMap<>();
    private static final Map<String, Glyph> RESOURCES = new HashMap<>();

    private GlyphTable() {
    }

    public static synchronized void put(String name, String font, char glyph) {
        TABLE.put(name, new Glyph(font, glyph));
    }

    public static synchronized void putResource(String name, String font, char glyph) {
        RESOURCES.put(name, new Glyph(font, glyph));
    }

    public static synchronized void clearResources() {
        RESOURCES.clear();
    }

    private static Glyph lookup(String name) {
        Glyph g = TABLE.get(name);
        return g != null ? g : RESOURCES.get(name);
    }

    public static synchronized boolean known(String name) {
        return lookup(name) != null;
    }

    public static synchronized Component expand(String text, int rgb) {
        MutableComponent out = Component.empty();
        Style base = Style.EMPTY.withColor(TextColor.fromRgb(rgb));
        int i = 0;
        int plainStart = 0;
        int n = text.length();
        while (i < n) {
            if (text.charAt(i) == ':') {
                int end = text.indexOf(':', i + 1);
                if (end > i + 1) {
                    Glyph g = lookup(text.substring(i + 1, end));
                    if (g != null) {
                        if (plainStart < i) {
                            out.append(Component.literal(text.substring(plainStart, i)).withStyle(base));
                        }
                        out.append(Component.literal(String.valueOf(g.glyph))
                                .withStyle(Fonts.apply(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF)), g.font)));
                        i = end + 1;
                        plainStart = i;
                        continue;
                    }
                }
            }
            i++;
        }
        if (plainStart < n) {
            out.append(Component.literal(text.substring(plainStart)).withStyle(base));
        }
        return out;
    }
}
