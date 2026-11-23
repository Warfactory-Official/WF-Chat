package com.wf.wfchat.emoji;

import com.wf.wfchat.core.Fonts;
import java.math.BigInteger;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;

/**
 * Wire format (compatible with Emojicord): {@code <:name:BASE64(id)>} for Discord emoji, {@code :name:}
 * for names, raw Unicode. Display: each emoji becomes one {@code wfchat:emoji} placeholder char.
 */
public final class EmojiText {
    private static final Pattern DISCORD = Pattern.compile("<(a?):(\\w+):([A-Za-z0-9+/=]+)>");
    private static final Pattern NAMED = Pattern.compile(":([\\w+-]+):");

    private EmojiText() {
    }

    /** Rewrites emoji tokens in a received line; the same instance comes back when there are none. */
    public static Component transform(Component c) {
        Emojis emojis = Emojis.get();
        if (!emojis.enabled()) {
            return c;
        }
        String plain = c.getString();
        if (!mayContain(plain)) {
            return c;
        }
        MutableComponent out = Component.empty();
        boolean[] changed = {false};
        c.visit((style, text) -> {
            run(emojis, out, text, style, changed);
            return Optional.empty();
        }, Style.EMPTY);
        return changed[0] ? out : c;
    }

    private static boolean mayContain(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == ':' || ch == '<' || ch >= 0x2000) {
                return true;
            }
        }
        return false;
    }

    private static void run(Emojis emojis, MutableComponent out, String text, Style style, boolean[] changed) {
        int plainStart = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            int end = -1;
            EmojiRef ref = null;
            if (ch == '<') {
                Matcher m = DISCORD.matcher(text).region(i, n);
                if (m.lookingAt()) {
                    ref = discord(emojis, m.group(2), m.group(3), !m.group(1).isEmpty());
                    end = m.end();
                }
            } else if (ch == ':') {
                Matcher m = NAMED.matcher(text).region(i, n);
                if (m.lookingAt()) {
                    ref = emojis.byName(m.group(1));
                    end = m.end();
                }
            } else if (ch >= 0x2000) {
                int[] hit = unicode(emojis, text, i);
                if (hit != null) {
                    ref = emojis.standardByUtf(Emojis.key(text.substring(i, hit[0])));
                    end = hit[0];
                }
            }
            if (ref != null) {
                if (plainStart < i) {
                    out.append(Component.literal(text.substring(plainStart, i)).withStyle(style));
                }
                out.append(Component.literal(String.valueOf(ref.ch)).withStyle(Fonts.apply(style, Emojis.FONT)));
                changed[0] = true;
                i = end;
                plainStart = i;
            } else {
                i++;
            }
        }
        if (plainStart < n) {
            out.append(Component.literal(text.substring(plainStart)).withStyle(style));
        }
    }

    @Nullable
    private static EmojiRef discord(Emojis emojis, String name, String id, boolean animated) {
        try {
            long value = id.chars().allMatch(Character::isDigit) && id.length() >= 15
                    ? Long.parseLong(id)
                    : new BigInteger(Base64.getDecoder().decode(id)).longValue();
            return emojis.discord(value, name, animated);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Longest dictionary sequence starting at {@code at}; returns {end index} or null. */
    @Nullable
    private static int[] unicode(Emojis emojis, String text, int at) {
        var dict = emojis.dictionary();
        int best = -1;
        int end = at;
        for (int count = 0; count < dict.maxCodepoints() + 3 && end < text.length(); count++) {
            end += Character.charCount(text.codePointAt(end));
            String key = Emojis.key(text.substring(at, end));
            if (dict.utfToName().containsKey(key)) {
                best = end;
            }
        }
        if (best < 0) {
            return null;
        }
        while (best < text.length() && text.charAt(best) == '️') {
            best++;
        }
        return new int[]{best};
    }

    /** Typed {@code :name:} of a configured Discord emoji becomes {@code <:name:BASE64>}. */
    public static String encode(String typed) {
        Emojis emojis = Emojis.get();
        if (!emojis.enabled() || typed.indexOf(':') < 0) {
            return typed;
        }
        Matcher m = NAMED.matcher(typed);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            EmojiConfig.Custom c = emojis.customId(name);
            m.appendReplacement(sb, Matcher.quoteReplacement(c == null ? m.group()
                    : "<" + (c.animated() ? "a" : "") + ":" + name + ":"
                    + Base64.getEncoder().encodeToString(BigInteger.valueOf(c.id()).toByteArray()) + ">"));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
