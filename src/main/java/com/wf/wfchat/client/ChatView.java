package com.wf.wfchat.client;

import com.wf.wfchat.core.ChatLog;
import com.wf.wfchat.emoji.EmojiRef;
import com.wf.wfchat.emoji.Emojis;
import com.wf.wfchat.core.Channels;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

/** Wrapped, flattened lines of the messages in the current selection, oldest first. */
public final class ChatView {
    private static final int MAX_LINES = 4000;
    private static final int KEEP_LINES = 2000;

    private final ClientChat chat;
    private FormattedCharSequence[] lines = new FormattedCharSequence[512];
    private int[] ticks = new int[512];
    private LineEmoji[] emojis = new LineEmoji[512];
    private int count;

    private long builtNext;
    private int builtSelection = -1;
    private int builtWidth = -1;
    private int builtFont = -1;
    private int builtChannels = -1;
    private boolean dirty = true;

    private int scroll;
    private boolean newSinceScroll;

    ChatView(ClientChat chat) {
        this.chat = chat;
    }

    public void invalidate() {
        dirty = true;
        scroll = 0;
        newSinceScroll = false;
    }

    public int count() {
        return count;
    }

    public FormattedCharSequence line(int index) {
        return lines[index];
    }

    /** Emoji placeholders on a line, or null. */
    public LineEmoji emojis(int index) {
        return emojis[index];
    }

    public int tick(int index) {
        return ticks[index];
    }

    public int scroll() {
        return scroll;
    }

    public boolean newSinceScroll() {
        return newSinceScroll;
    }

    public void scrollBy(int lines, int visible) {
        scroll = Math.max(0, Math.min(Math.max(0, count - visible), scroll + lines));
        if (scroll == 0) {
            newSinceScroll = false;
        }
    }

    public void resetScroll() {
        scroll = 0;
        newSinceScroll = false;
    }

    /** Brings the line list up to date for {@code width} pixels of text. */
    public void ensure(int width, int fontVersion) {
        if (dirty || builtSelection != chat.selection.version() || builtWidth != width || builtFont != fontVersion
                || builtChannels != chat.channels.version()) {
            rebuild(width, fontVersion);
            return;
        }
        if (builtNext != chat.log.nextSeq()) {
            int before = count;
            append(width);
            int added = count - before;
            if (added > 0 && scroll > 0) {
                scroll += added;
                newSinceScroll = true;
            }
        }
    }

    private void rebuild(int width, int fontVersion) {
        builtSelection = chat.selection.version();
        builtWidth = width;
        builtFont = fontVersion;
        builtChannels = chat.channels.version();
        dirty = false;
        count = 0;
        builtNext = chat.log.firstSeq();
        append(width);
        scroll = Math.min(scroll, count);
    }

    private void append(int width) {
        ChatLog<Message> log = chat.log;
        boolean prefixed = chat.selection.size() > 1;
        var font = Minecraft.getInstance().font;
        for (long seq = Math.max(builtNext, log.firstSeq()); seq < log.nextSeq(); seq++) {
            ChatLog.Entry<Message> e = log.get(seq);
            String origin = chat.selection.origin(e.channels);
            if (origin == null) {
                continue;
            }
            Component shown = e.payload.content;
            if (prefixed) {
                Channels.Channel c = chat.channels.get(origin);
                if (c != null) {
                    MutableComponent merged = Component.empty().append(c.prefix).append(shown);
                    shown = merged;
                }
            }
            List<FormattedCharSequence> split = font.split(shown, width);
            for (FormattedCharSequence part : split) {
                push(part, e.payload.tick);
            }
        }
        builtNext = log.nextSeq();
        if (count > MAX_LINES) {
            int drop = count - KEEP_LINES;
            System.arraycopy(lines, drop, lines, 0, KEEP_LINES);
            System.arraycopy(ticks, drop, ticks, 0, KEEP_LINES);
            System.arraycopy(emojis, drop, emojis, 0, KEEP_LINES);
            java.util.Arrays.fill(lines, KEEP_LINES, count, null);
            java.util.Arrays.fill(emojis, KEEP_LINES, count, null);
            count = KEEP_LINES;
        }
    }

    private void push(FormattedCharSequence line, int tick) {
        if (count == lines.length) {
            lines = java.util.Arrays.copyOf(lines, count * 2);
            ticks = java.util.Arrays.copyOf(ticks, count * 2);
            emojis = java.util.Arrays.copyOf(emojis, count * 2);
        }
        lines[count] = line;
        ticks[count] = tick;
        emojis[count] = LineEmoji.scan(line);
        count++;
    }

    /** Emoji placeholders and spoiler runs within one wrapped line (pixels from the line start). */
    public static final class LineEmoji {
        public final float[] x;
        public final EmojiRef[] ref;
        public final float[] spoilerFrom;
        public final float[] spoilerTo;
        public final long[] spoilerId;
        /** Decoration runs Canvas does not draw: kind 1 underline, 2 strikethrough. */
        public final float[] decoFrom;
        public final float[] decoTo;
        public final int[] decoKind;
        public final int[] decoRgb;

        private LineEmoji(float[] x, EmojiRef[] ref, float[] from, float[] to, long[] id, float[] dFrom, float[] dTo,
                          int[] dKind, int[] dRgb) {
            this.decoFrom = dFrom;
            this.decoTo = dTo;
            this.decoKind = dKind;
            this.decoRgb = dRgb;
            this.x = x;
            this.ref = ref;
            this.spoilerFrom = from;
            this.spoilerTo = to;
            this.spoilerId = id;
        }

        static LineEmoji scan(FormattedCharSequence line) {
            java.util.List<EmojiRef> refs = new java.util.ArrayList<>(2);
            java.util.List<Float> xs = new java.util.ArrayList<>(2);
            java.util.List<Float> from = new java.util.ArrayList<>(1);
            java.util.List<Float> to = new java.util.ArrayList<>(1);
            java.util.List<Long> ids = new java.util.ArrayList<>(1);
            java.util.List<Float> dFrom = new java.util.ArrayList<>(2);
            java.util.List<Float> dTo = new java.util.ArrayList<>(2);
            java.util.List<Integer> dKind = new java.util.ArrayList<>(2);
            java.util.List<Integer> dRgb = new java.util.ArrayList<>(2);
            float[] cursor = {0};
            var font = Minecraft.getInstance().font;
            line.accept((pos, style, cp) -> {
                EmojiRef r = cp >= 0xE000 && cp < 0xE800 ? Emojis.get().byChar((char) cp) : null;
                float start = cursor[0];
                if (r != null) {
                    refs.add(r);
                    xs.add(start);
                    cursor[0] += 9;
                } else {
                    cursor[0] += font.width(FormattedCharSequence.codepoint(cp, style));
                }
                for (int kind = 1; kind <= 2; kind++) {
                    if (kind == 1 ? style.isUnderlined() : style.isStrikethrough()) {
                        int rgb = style.getColor() != null ? style.getColor().getValue() : 0xFFFFFF;
                        int last = dKind.size() - 1;
                        if (last >= 0 && dKind.get(last) == kind && dRgb.get(last) == rgb && dTo.get(last) == start) {
                            dTo.set(last, cursor[0]);
                        } else {
                            dFrom.add(start);
                            dTo.add(cursor[0]);
                            dKind.add(kind);
                            dRgb.add(rgb);
                        }
                    }
                }
                long sid = StyleText.spoilerId(style);
                if (sid != 0) {
                    int last = ids.size() - 1;
                    if (last >= 0 && ids.get(last) == sid) {
                        to.set(last, cursor[0]);
                    } else {
                        ids.add(sid);
                        from.add(start);
                        to.add(cursor[0]);
                    }
                }
                return true;
            });
            if (refs.isEmpty() && ids.isEmpty() && dKind.isEmpty()) {
                return null;
            }
            return new LineEmoji(floats(xs), refs.toArray(new EmojiRef[0]), floats(from), floats(to), longs(ids),
                    floats(dFrom), floats(dTo), ints(dKind), ints(dRgb));
        }

        private static float[] floats(java.util.List<Float> list) {
            float[] out = new float[list.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = list.get(i);
            }
            return out;
        }

        private static int[] ints(java.util.List<Integer> list) {
            int[] out = new int[list.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = list.get(i);
            }
            return out;
        }

        private static long[] longs(java.util.List<Long> list) {
            long[] out = new long[list.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = list.get(i);
            }
            return out;
        }
    }
}
