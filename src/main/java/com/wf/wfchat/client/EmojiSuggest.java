package com.wf.wfchat.client;

import com.wf.gemrender.ui.Canvas;
import com.wf.wfchat.emoji.EmojiRef;
import com.wf.wfchat.emoji.Emojis;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.EditBox;

/** {@code :na} in the input line pops up matching emoji; Tab / click completes, Up / Down move, Esc dismisses. */
public final class EmojiSuggest {
    private static final int ROWS = 8;
    private static final int ROW_H = 12;
    private static final int KEY_TAB = 258;
    private static final int KEY_ESC = 256;
    private static final int KEY_DOWN = 264;
    private static final int KEY_UP = 265;

    private static String query = "";
    private static String dismissed = "";
    private static int start = -1;
    private static int selected;
    private static final List<String> items = new ArrayList<>();
    private static final List<EmojiRef> refs = new ArrayList<>();
    private static float left;
    private static float top;
    private static float width;

    private EmojiSuggest() {
    }

    public static boolean open() {
        return !items.isEmpty();
    }

    public static void update(EditBox input) {
        String text = input.getValue();
        int cursor = input.getCursorPosition();
        int i = cursor - 1;
        while (i >= 0 && isNameChar(text.charAt(i))) {
            i--;
        }
        String q = i >= 0 && text.charAt(i) == ':' && cursor - i - 1 >= 2 ? text.substring(i + 1, cursor) : "";
        if (q.equals(query)) {
            return;
        }
        query = q;
        start = i;
        selected = 0;
        items.clear();
        refs.clear();
        if (!q.isEmpty() && !q.equals(dismissed)) {
            for (String name : Emojis.get().suggest(q, ROWS)) {
                EmojiRef r = Emojis.get().byName(name);
                if (r != null) {
                    items.add(name);
                    refs.add(r);
                }
            }
        }
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '+' || c == '-';
    }

    public static void render(Canvas c, EditBox input, int guiHeight, int mouseX, int mouseY, int bottomY) {
        update(input);
        if (items.isEmpty()) {
            return;
        }
        float w = 0;
        for (String s : items) {
            w = Math.max(w, c.width(s));
        }
        width = w + 24;
        left = 4;
        top = bottomY - items.size() * ROW_H;
        c.fill(left, top, left + width, bottomY, 0xC0000000);
        for (int i = 0; i < items.size(); i++) {
            float y = top + i * ROW_H;
            boolean hover = mouseX >= left && mouseX < left + width && mouseY >= y && mouseY < y + ROW_H;
            if (i == selected || hover) {
                c.fill(left, y, left + width, y + ROW_H, i == selected ? 0xC02E9E2E : 0x60FFFFFF);
            }
            ChatHud.drawEmoji(c, refs.get(i), left + 2, y + 2, 255);
            c.text(items.get(i), left + 14, y + 2, 0xFFFFFFFF, true);
        }
    }

    /** @return true when the key was consumed */
    public static boolean key(int keyCode, EditBox input) {
        if (items.isEmpty()) {
            return false;
        }
        switch (keyCode) {
            case KEY_TAB -> accept(selected, input);
            case KEY_UP -> selected = (selected + items.size() - 1) % items.size();
            case KEY_DOWN -> selected = (selected + 1) % items.size();
            case KEY_ESC -> {
                dismissed = query;
                items.clear();
                refs.clear();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    public static boolean click(double mouseX, double mouseY, EditBox input) {
        if (items.isEmpty() || mouseX < left || mouseX >= left + width) {
            return false;
        }
        int row = (int) ((mouseY - top) / ROW_H);
        if (mouseY < top || row < 0 || row >= items.size()) {
            return false;
        }
        accept(row, input);
        return true;
    }

    private static void accept(int row, EditBox input) {
        String text = input.getValue();
        int cursor = input.getCursorPosition();
        String insert = ":" + items.get(row) + ": ";
        input.setValue(text.substring(0, start) + insert + text.substring(cursor));
        input.setCursorPosition(start + insert.length());
        query = "";
        items.clear();
        refs.clear();
    }
}
