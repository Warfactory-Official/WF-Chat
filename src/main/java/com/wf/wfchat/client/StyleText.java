package com.wf.wfchat.client;

import com.wf.wfchat.core.ChatTypes;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jetbrains.annotations.Nullable;

/**
 * Markup in the body of received chat lines: {@code **bold**}, {@code ~~strike~~}, {@code __underline__},
 * {@code ||spoiler||}, {@code &#RRGGBB}/{@code &#RGB} colour until {@code &r} or end of run. A marker opens only when
 * a closer follows in the same text run; {@code \} escapes. Spoiler runs carry insertion {@code wfchat:sp=<id>}.
 */
public final class StyleText {
    public static final String SPOILER = "wfchat:sp=";
    private static long nextSpoiler = 1;

    private StyleText() {
    }

    /** Spoiler id of {@code style}, or 0. */
    public static long spoilerId(Style style) {
        String ins = style.getInsertion();
        if (ins == null || !ins.startsWith(SPOILER)) {
            return 0;
        }
        try {
            return Long.parseLong(ins.substring(SPOILER.length()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** Styles the message argument of a chat decoration; other lines come back unchanged. */
    public static Component apply(Component content, ChatConfig config) {
        if (!config.styleEnabled) {
            return content;
        }
        TranslatableContents t = ChatTypes.translatable(content);
        if (t == null || t.getArgs().length < 2) {
            return content;
        }
        Object[] args = t.getArgs();
        Object last = args[args.length - 1];
        Component body = last instanceof Component c ? c : Component.literal(String.valueOf(last));
        Component styled = transform(body, config);
        if (styled == body) {
            return content;
        }
        Object[] copy = args.clone();
        copy[copy.length - 1] = styled;
        MutableComponent out = Component.translatableWithFallback(t.getKey(), t.getFallback(), copy)
                .setStyle(content.getStyle());
        content.getSiblings().forEach(out::append);
        return out;
    }

    static Component transform(Component c, ChatConfig config) {
        String plain = c.getString();
        if (plain.indexOf('*') < 0 && plain.indexOf('~') < 0 && plain.indexOf('_') < 0 && plain.indexOf('|') < 0
                && plain.indexOf('&') < 0 && plain.indexOf('\\') < 0) {
            return c;
        }
        MutableComponent out = Component.empty();
        boolean[] changed = {false};
        c.visit((style, text) -> {
            new Run(out, text, style, config, changed).parse();
            return Optional.empty();
        }, Style.EMPTY);
        return changed[0] ? out : c;
    }

    private static final class Run {
        private final MutableComponent out;
        private final String text;
        private final Style base;
        private final ChatConfig config;
        private final boolean[] changed;
        private final StringBuilder cur = new StringBuilder();
        private boolean bold;
        private boolean strike;
        private boolean under;
        private long spoiler;
        private @Nullable TextColor color;

        Run(MutableComponent out, String text, Style base, ChatConfig config, boolean[] changed) {
            this.out = out;
            this.text = text;
            this.base = base;
            this.config = config;
            this.changed = changed;
        }

        void parse() {
            int n = text.length();
            int i = 0;
            while (i < n) {
                char c = text.charAt(i);
                if (c == '\\' && i + 1 < n && "*~_|&\\".indexOf(text.charAt(i + 1)) >= 0) {
                    cur.append(text.charAt(i + 1));
                    changed[0] = true;
                    i += 2;
                } else if (c == ':') {
                    int end = emojiToken(i);
                    cur.append(text, i, end);
                    i = end;
                } else if (c == '&' && config.colors && i + 1 < n && (text.charAt(i + 1) == '#' || text.charAt(i + 1) == 'r')) {
                    i = colour(i);
                } else if (config.formatting && text.startsWith("**", i)) {
                    i = toggle(i, "**", 0);
                } else if (config.formatting && text.startsWith("~~", i)) {
                    i = toggle(i, "~~", 1);
                } else if (config.formatting && text.startsWith("__", i)) {
                    i = toggle(i, "__", 2);
                } else if (config.spoilers && text.startsWith("||", i)) {
                    i = toggle(i, "||", 3);
                } else {
                    cur.append(c);
                    i++;
                }
            }
            flush();
        }

        /** {@code :name:} is not markup: underscores inside belong to the name. */
        private int emojiToken(int i) {
            int j = i + 1;
            while (j < text.length() && (Character.isLetterOrDigit(text.charAt(j)) || "_+-".indexOf(text.charAt(j)) >= 0)) {
                j++;
            }
            return j < text.length() && text.charAt(j) == ':' && j > i + 1 ? j + 1 : i + 1;
        }

        private int colour(int i) {
            if (text.charAt(i + 1) == 'r') {
                flush();
                color = null;
                changed[0] = true;
                return i + 2;
            }
            int digits = 0;
            while (i + 2 + digits < text.length() && digits < 6 && Character.digit(text.charAt(i + 2 + digits), 16) >= 0) {
                digits++;
            }
            if (digits != 6 && digits != 3) {
                cur.append('&');
                return i + 1;
            }
            int rgb = Integer.parseInt(text.substring(i + 2, i + 2 + digits), 16);
            if (digits == 3) {
                rgb = (rgb >> 8 & 0xF) * 0x110000 | (rgb >> 4 & 0xF) * 0x1100 | (rgb & 0xF) * 0x11;
            }
            flush();
            color = TextColor.fromRgb(rgb);
            changed[0] = true;
            return i + 2 + digits;
        }

        private int toggle(int i, String marker, int which) {
            boolean active = switch (which) {
                case 0 -> bold;
                case 1 -> strike;
                case 2 -> under;
                default -> spoiler != 0;
            };
            if (!active && text.indexOf(marker, i + 3) < 0) {
                cur.append(marker.charAt(0));
                return i + 1;
            }
            flush();
            changed[0] = true;
            switch (which) {
                case 0 -> bold = !active;
                case 1 -> strike = !active;
                case 2 -> under = !active;
                default -> spoiler = active ? 0 : nextSpoiler++;
            }
            return i + 2;
        }

        private void flush() {
            if (cur.length() == 0) {
                return;
            }
            Style s = base;
            if (bold) {
                s = s.withBold(true);
            }
            if (strike) {
                s = s.withStrikethrough(true);
            }
            if (under) {
                s = s.withUnderlined(true);
            }
            if (color != null) {
                s = s.withColor(color);
            }
            if (spoiler != 0) {
                s = s.withInsertion(SPOILER + spoiler);
            }
            out.append(Component.literal(cur.toString()).withStyle(s));
            cur.setLength(0);
        }
    }
}
