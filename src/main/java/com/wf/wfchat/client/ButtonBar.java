package com.wf.wfchat.client;

import com.wf.gemrender.ui.Canvas;
import com.wf.gemrender.ui.TextBuffer;
import com.wf.wfchat.core.Channels;
import net.minecraft.client.Minecraft;
import net.minecraft.util.FormattedCharSequence;

/** Channel buttons between the history and the input line. Flat, transparent; selected = green. Zero allocation per frame. */
public final class ButtonBar {
    public static final int HEIGHT = 12;
    private static final int PAD = 4;
    private static final int GAP = 2;
    private static final int FROM_BOTTOM = 27;
    private static final int SELECTED = 0xC02E9E2E;
    private static final int SELECTED_HOVER = 0xD83CB83C;
    private static final int HOVER = 0xA0404040;

    private final TextBuffer badge = new TextBuffer(8);
    private FormattedCharSequence[] labels = new FormattedCharSequence[8];
    private float[] left = new float[8];
    private float[] right = new float[8];
    private int builtVersion = -1;
    private int builtFont = -1;
    private int count;
    private int top;

    private void layout(Canvas c, int guiHeight, ClientChat chat) {
        Channels channels = chat.channels;
        int n = channels.size();
        if (builtVersion != channels.version() || builtFont != c.fontVersion()) {
            builtVersion = channels.version();
            builtFont = c.fontVersion();
            if (labels.length < n) {
                labels = new FormattedCharSequence[n * 2];
                left = new float[n * 2];
                right = new float[n * 2];
            }
            for (int i = 0; i < n; i++) {
                labels[i] = channels.at(i).spec.title().getVisualOrderText();
            }
        }
        count = n;
        top = guiHeight - FROM_BOTTOM;
        float x = 4;
        for (int i = 0; i < n; i++) {
            float w = c.width(labels[i]) + PAD * 2;
            int unread = channels.at(i).unread;
            if (unread > 0) {
                badge.clear().append(' ').append(Math.min(unread, 99L));
                w += c.width(badge);
            }
            left[i] = x;
            right[i] = x + w;
            x += w + GAP;
        }
    }

    void render(Canvas c, int guiWidth, int guiHeight, int mouseX, int mouseY, ClientChat chat) {
        layout(c, guiHeight, chat);
        int defaultBg = Minecraft.getInstance().options.getBackgroundColor(0x80000000);
        boolean multi = chat.selection.size() > 1;
        for (int i = 0; i < count; i++) {
            Channels.Channel ch = chat.channels.at(i);
            boolean selected = chat.selection.contains(ch.spec.id());
            boolean hovered = mouseX >= left[i] && mouseX < right[i] && mouseY >= top && mouseY < top + HEIGHT;
            int bg = selected ? (hovered ? SELECTED_HOVER : SELECTED) : (hovered ? HOVER : defaultBg);
            c.fill(left[i], top, right[i], top + HEIGHT, bg);
            int color = selected ? 0xFFFFFFFF : 0xFF000000 | ch.spec.color();
            float end = c.text(labels[i], left[i] + PAD, top + 2, color, true);
            if (ch.unread > 0) {
                badge.clear().append(' ').append(Math.min(ch.unread, 99L));
                c.text(badge, end, top + 2, 0xFFFFDD55, true);
            }
            if (multi && selected && ch.spec.id().equals(chat.selection.target())) {
                c.fill(left[i] + PAD, top + HEIGHT - 2, right[i] - PAD, top + HEIGHT - 1, 0xFFFFFFFF);
            }
        }
    }

    public int buttons() {
        return count;
    }

    public float centerX(int index) {
        return (left[index] + right[index]) * 0.5f;
    }

    public float centerY() {
        return top + HEIGHT * 0.5f;
    }

    /** @return true when the click landed on a button and was handled */
    public boolean click(double mouseX, double mouseY, int button, boolean shift) {
        if (mouseY < top || mouseY >= top + HEIGHT) {
            return false;
        }
        ClientChat chat = ClientChat.get();
        for (int i = 0; i < count; i++) {
            if (mouseX >= left[i] && mouseX < right[i]) {
                Channels.Channel ch = chat.channels.at(i);
                if (button == 1 && ch.spec.dynamic()) {
                    chat.close(ch.spec.id());
                } else if (button == 0) {
                    chat.select(ch.spec.id(), shift);
                } else {
                    return false;
                }
                return true;
            }
        }
        return false;
    }
}
