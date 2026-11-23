package com.wf.wfchat.client;

import com.wf.gemrender.ui.Canvas;
import com.wf.wfchat.emoji.EmojiRef;
import com.wf.wfchat.emoji.Emojis;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.player.LocalPlayer;

/** Square buttons at the right end of the channel bar: emoji picker, colour picker, insert coordinates. */
public final class ToolButtons {
    private static final int SIZE = ButtonBar.HEIGHT;
    private static final int GAP = 2;
    private static final int FROM_BOTTOM = 27;
    private static final int KEY_ESC = 256;
    private static final int KEY_ENTER = 257;
    private static final int KEY_KP_ENTER = 335;
    private static final int KEY_BACKSPACE = 259;
    private static final int NONE = -1;
    private static final int EMOJI = 0;
    private static final int COLOR = 1;
    private static final int COORDS = 2;

    private static final int COLS = 9;
    private static final int ROWS = 6;
    private static final int CELL = 13;
    private static final int WHEEL_CELLS = 36;
    private static final int WHEEL_CELL = 2;
    private static final float WHEEL_R = WHEEL_CELLS * WHEEL_CELL / 2f;
    private static final int SLIDER_W = 10;
    private static final int HEADER = 12;
    private static final int FOOTER = 12;
    private static final int PANEL = 0xE0101010;

    private static int open = NONE;
    private static float value = 1f;
    private static int page;
    private static List<String> names = List.of();
    private static final List<String> TAB_LABELS = new ArrayList<>();
    private static final List<List<String>> TAB_LISTS = new ArrayList<>();
    private static final StringBuilder SEARCH = new StringBuilder();
    private static int tab;
    private static final int TAB = 12;
    private static final int TABW = 11;
    private static final int EHEADER = 2 * 12;
    private static final int SEARCH_MAX = 24;
    private static String hover = "";
    private static float panelLeft;
    private static float panelTop;
    private static float panelRight;
    private static float panelBottom;
    private static float barTop;
    private static float barLeft;

    private ToolButtons() {
    }

    public static void close() {
        open = NONE;
    }

    public static float centerX(int index) {
        return buttonLeft(index) + SIZE * 0.5f;
    }

    public static float centerY() {
        return barTop + SIZE * 0.5f;
    }

    /** Centre of cell {@code index} of the open panel (emoji: grid cell; colour: swatch column index, row 3). */
    public static float cellX(int index) {
        return open == EMOJI ? panelLeft + 2 + (index % COLS) * CELL + CELL / 2f : wheelCx() + WHEEL_R / 2f;
    }

    public static float cellY(int index) {
        return open == EMOJI ? panelTop + EHEADER + (index / COLS) * CELL + CELL / 2f : wheelCy();
    }

    public static int tabCount() {
        return TAB_LABELS.size();
    }

    public static float tabX(int index) {
        return panelLeft + 2 + index * TABW + TABW / 2f;
    }

    public static float tabY() {
        return panelTop + TAB / 2f;
    }

    public static String hoverText() {
        return hover;
    }

    public static boolean isOpen() {
        return open != NONE;
    }

    private static float buttonLeft(int i) {
        return barLeft + i * (SIZE + GAP);
    }

    private static int pages() {
        return Math.max(1, (names.size() + COLS * ROWS - 1) / (COLS * ROWS));
    }

    public static void render(Canvas c, int guiWidth, int guiHeight, int mouseX, int mouseY) {
        barTop = guiHeight - FROM_BOTTOM;
        barLeft = guiWidth - 4 - 3 * SIZE - 2 * GAP;
        for (int i = 0; i < 3; i++) {
            float x = buttonLeft(i);
            boolean hovered = mouseX >= x && mouseX < x + SIZE && mouseY >= barTop && mouseY < barTop + SIZE;
            int bg = open == i ? 0xC02E9E2E : hovered ? 0xA0404040 : Minecraft.getInstance().options.getBackgroundColor(0x80000000);
            c.fill(x, barTop, x + SIZE, barTop + SIZE, bg);
            icon(c, i, x, barTop);
        }
        if (open == EMOJI) {
            emojiPanel(c, guiWidth, mouseX, mouseY);
        } else if (open == COLOR) {
            colorPanel(c, mouseX, mouseY);
        }
    }

    private static void icon(Canvas c, int which, float x, float y) {
        switch (which) {
            case EMOJI -> {
                EmojiRef smile = Emojis.get().standardByUtf("😀");
                if (smile != null) {
                    ChatHud.drawEmoji(c, smile, x + 1.5f, y + 1.5f, 255);
                }
            }
            case COLOR -> {
                c.fill(x + 2, y + 2, x + 6, y + 6, 0xFFE04040);
                c.fill(x + 6, y + 2, x + 10, y + 6, 0xFF40C040);
                c.fill(x + 2, y + 6, x + 6, y + 10, 0xFF4060E0);
                c.fill(x + 6, y + 6, x + 10, y + 10, 0xFFE0C040);
            }
            default -> {
                c.fill(x + 5, y + 2, x + 7, y + 10, 0xFFFFFFFF);
                c.fill(x + 2, y + 5, x + 10, y + 7, 0xFFFFFFFF);
            }
        }
    }

    private static void emojiPanel(Canvas c, int guiWidth, int mouseX, int mouseY) {
        float w = COLS * CELL + 4;
        float h = EHEADER + ROWS * CELL + FOOTER + 2;
        panelRight = guiWidth - 4;
        panelLeft = panelRight - w;
        panelBottom = barTop - 2;
        panelTop = panelBottom - h;
        c.fill(panelLeft, panelTop, panelRight, panelBottom, PANEL);
        hover = "";
        boolean searching = SEARCH.length() > 0;
        for (int t = 0; t < TAB_LABELS.size(); t++) {
            float x = panelLeft + 2 + t * TABW;
            boolean over = mouseOver(mouseX, mouseY, x, panelTop, TABW, TAB);
            if (t == tab && !searching) {
                c.fill(x, panelTop + 1, x + TABW, panelTop + TAB, 0xC02E9E2E);
            } else if (over) {
                c.fill(x, panelTop + 1, x + TABW, panelTop + TAB, 0x60FFFFFF);
            }
            if (over) {
                hover = TAB_LABELS.get(t);
            }
            List<String> list = TAB_LISTS.get(t);
            EmojiRef icon = TAB_LABELS.get(t).equals("Custom") || list.isEmpty() ? null : Emojis.get().byName(list.get(0));
            if (icon != null) {
                ChatHud.drawEmoji(c, icon, x + 1f, panelTop + 2, 255);
            } else {
                c.text("C", x + 3, panelTop + 2, 0xFFFFDD55, true);
            }
        }
        float rowY = panelTop + TAB;
        c.fill(panelLeft + 2, rowY + 1, panelLeft + 2 + 66, rowY + TAB - 1, 0xFF000000);
        c.text(searching ? SEARCH + "_" : "search...", panelLeft + 4, rowY + 2, searching ? 0xFFFFFFFF : 0xFF777777, true);
        String label = (page + 1) + "/" + pages();
        c.text(label, panelRight - 50, rowY + 2, 0xFFAAAAAA, true);
        c.text("<", panelRight - 22, rowY + 2, mouseOver(mouseX, mouseY, panelRight - 24, rowY, 10, TAB) ? 0xFFFFFF55 : 0xFFFFFFFF, true);
        c.text(">", panelRight - 11, rowY + 2, mouseOver(mouseX, mouseY, panelRight - 13, rowY, 11, TAB) ? 0xFFFFFF55 : 0xFFFFFFFF, true);
        int first = page * COLS * ROWS;
        for (int i = 0; i < COLS * ROWS && first + i < names.size(); i++) {
            float x = panelLeft + 2 + (i % COLS) * CELL;
            float y = panelTop + EHEADER + (i / COLS) * CELL;
            String name = names.get(first + i);
            if (mouseOver(mouseX, mouseY, x, y, CELL, CELL)) {
                hover = ":" + name + ":";
                c.fill(x, y, x + CELL, y + CELL, 0x60FFFFFF);
            }
            EmojiRef ref = Emojis.get().byName(name);
            if (ref != null) {
                ChatHud.drawEmoji(c, ref, x + 2, y + 2, 255);
            }
        }
        if (names.isEmpty()) {
            c.text(Emojis.get().categories().isEmpty() ? "loading..." : "no matches", panelLeft + 6, panelTop + EHEADER + 4, 0xFFAAAAAA, true);
        }
        c.text(hover, panelLeft + 4, panelBottom - FOOTER + 1, 0xFFFFFFFF, true);
    }

    private static float wheelCx() {
        return panelLeft + 4 + WHEEL_R;
    }

    private static float wheelCy() {
        return panelTop + HEADER + 2 + WHEEL_R;
    }

    private static float sliderLeft() {
        return panelLeft + 4 + 2 * WHEEL_R + 6;
    }

    /** RGB under the pointer on the wheel at the current brightness, or -1 outside it. */
    private static int wheelColor(double mx, double my) {
        double dx = mx - wheelCx();
        double dy = my - wheelCy();
        double r = Math.sqrt(dx * dx + dy * dy);
        if (r > WHEEL_R) {
            return -1;
        }
        double hue = Math.atan2(dy, dx) / (2 * Math.PI);
        return hsv((float) hue, (float) (r / WHEEL_R), value);
    }

    private static void colorPanel(Canvas c, int mouseX, int mouseY) {
        float w = 4 + 2 * WHEEL_R + 6 + SLIDER_W + 8 + 34;
        float h = HEADER + 2 + 2 * WHEEL_R + 4;
        panelRight = barLeft + 2 * (SIZE + GAP) + SIZE;
        panelLeft = panelRight - w;
        panelBottom = barTop - 2;
        panelTop = panelBottom - h;
        c.fill(panelLeft, panelTop, panelRight, panelBottom, PANEL);
        c.text("colour", panelLeft + 4, panelTop + 2, 0xFFAAAAAA, true);
        float cx = wheelCx();
        float cy = wheelCy();
        for (int j = 0; j < WHEEL_CELLS; j++) {
            for (int i = 0; i < WHEEL_CELLS; i++) {
                float x = cx - WHEEL_R + i * WHEEL_CELL;
                float y = cy - WHEEL_R + j * WHEEL_CELL;
                float dx = x + WHEEL_CELL / 2f - cx;
                float dy = y + WHEEL_CELL / 2f - cy;
                float r = (float) Math.sqrt(dx * dx + dy * dy);
                if (r <= WHEEL_R) {
                    c.fill(x, y, x + WHEEL_CELL, y + WHEEL_CELL,
                            0xFF000000 | hsv((float) (Math.atan2(dy, dx) / (2 * Math.PI)), r / WHEEL_R, value));
                }
            }
        }
        float sx = sliderLeft();
        for (int j = 0; j < WHEEL_CELLS; j++) {
            float y = cy - WHEEL_R + j * WHEEL_CELL;
            int g = Math.round(255f * (1f - j / (float) (WHEEL_CELLS - 1)));
            c.fill(sx, y, sx + SLIDER_W, y + WHEEL_CELL, 0xFF000000 | g << 16 | g << 8 | g);
        }
        float markY = cy - WHEEL_R + (1f - value) * (WHEEL_CELLS - 1) * WHEEL_CELL;
        c.frame(sx - 1, markY - 1, sx + SLIDER_W + 1, markY + WHEEL_CELL + 1, 1, 0xFFFFFFFF);
        int hovered = wheelColor(mouseX, mouseY);
        float px = sx + SLIDER_W + 8;
        int shown = hovered >= 0 ? hovered : hsv(0f, 0f, value);
        c.fill(px, cy - WHEEL_R, px + 30, cy - WHEEL_R + 30, 0xFF000000 | shown);
        c.text(hovered >= 0 ? hex(hovered) : "", px - 1, cy - WHEEL_R + 34, 0xFFFFFFFF, true);
    }

    private static boolean mouseOver(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    static int hsv(float h, float s, float v) {
        float h6 = (h - (float) Math.floor(h)) * 6f;
        int i = (int) h6;
        float f = h6 - i;
        float p = v * (1 - s);
        float q = v * (1 - s * f);
        float t = v * (1 - s * (1 - f));
        float r;
        float g;
        float b;
        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
    }

    static String hex(int rgb) {
        return String.format("#%06X", rgb);
    }

    /** @return true when the click was consumed (button, or inside an open panel); outside clicks close the panel. */
    public static boolean click(double mouseX, double mouseY, boolean shift, EditBox input) {
        for (int i = 0; i < 3; i++) {
            if (mouseOver(mouseX, mouseY, buttonLeft(i), barTop, SIZE, SIZE)) {
                activate(i, input);
                return true;
            }
        }
        if (open == NONE) {
            return false;
        }
        if (!mouseOver(mouseX, mouseY, panelLeft, panelTop, panelRight - panelLeft, panelBottom - panelTop)) {
            open = NONE;
            return false;
        }
        if (open == EMOJI) {
            emojiClick(mouseX, mouseY, shift, input);
        } else {
            colorClick(mouseX, mouseY, input);
        }
        return true;
    }

    private static void activate(int which, EditBox input) {
        if (which == COORDS) {
            open = NONE;
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null) {
                input.insertText(p.getBlockX() + " " + p.getBlockY() + " " + p.getBlockZ() + " ");
            }
            return;
        }
        if (open == which) {
            open = NONE;
            return;
        }
        open = which;
        if (which == EMOJI) {
            loadTabs();
        }
    }

    private static void emojiClick(double mx, double my, boolean shift, EditBox input) {
        if (my < panelTop + TAB) {
            int t = (int) ((mx - panelLeft - 2) / TABW);
            if (t >= 0 && t < TAB_LABELS.size()) {
                tab = t;
                SEARCH.setLength(0);
                refresh();
            }
            return;
        }
        if (my < panelTop + EHEADER) {
            if (mx > panelRight - 24 && mx < panelRight - 13) {
                page = (page + pages() - 1) % pages();
            } else if (mx >= panelRight - 13) {
                page = (page + 1) % pages();
            }
            return;
        }
        int col = (int) ((mx - panelLeft - 2) / CELL);
        int row = (int) ((my - panelTop - EHEADER) / CELL);
        int idx = page * COLS * ROWS + row * COLS + col;
        if (col >= 0 && col < COLS && row >= 0 && row < ROWS && idx < names.size()) {
            pick(names.get(idx), shift, input);
        }
    }

    private static void pick(String name, boolean keepOpen, EditBox input) {
        input.insertText(":" + name + ": ");
        if (!keepOpen) {
            open = NONE;
        }
    }

    private static void refresh() {
        names = SEARCH.length() > 0 ? Emojis.get().search(SEARCH.toString())
                : tab < TAB_LISTS.size() ? TAB_LISTS.get(tab) : List.of();
        page = 0;
    }

    private static void loadTabs() {
        TAB_LABELS.clear();
        TAB_LISTS.clear();
        List<String> custom = Emojis.get().customNames();
        if (!custom.isEmpty()) {
            TAB_LABELS.add("Custom");
            TAB_LISTS.add(custom);
        }
        for (Map.Entry<String, List<String>> e : Emojis.get().categories().entrySet()) {
            TAB_LABELS.add(e.getKey());
            TAB_LISTS.add(e.getValue());
        }
        tab = Math.min(tab, Math.max(0, TAB_LABELS.size() - 1));
        SEARCH.setLength(0);
        refresh();
    }

    /** Character typed while the emoji panel is open goes to its search field. */
    public static boolean typed(EditBox box, int codepoint) {
        if (open != EMOJI || !(Minecraft.getInstance().screen instanceof ChatScreenAccess a) || a.wfchat$input() != box) {
            return false;
        }
        if (codepoint > 32 && SEARCH.length() < SEARCH_MAX && !Character.isISOControl(codepoint)) {
            SEARCH.appendCodePoint(codepoint);
            refresh();
        }
        return true;
    }

    private static void colorClick(double mx, double my, EditBox input) {
        int rgb = wheelColor(mx, my);
        if (rgb >= 0) {
            input.insertText("&#" + String.format("%06x", rgb));
            open = NONE;
            return;
        }
        float sx = sliderLeft();
        float top = wheelCy() - WHEEL_R;
        if (mx >= sx - 2 && mx < sx + SLIDER_W + 2 && my >= top && my < top + 2 * WHEEL_R) {
            value = 1f - (float) ((my - top) / (2 * WHEEL_R));
            value = Math.max(0f, Math.min(1f, value));
        }
    }

    /** @return true when the key was consumed */
    public static boolean key(int keyCode, EditBox input) {
        if (open == NONE) {
            return false;
        }
        if (keyCode == KEY_ESC) {
            open = NONE;
            return true;
        }
        if (open != EMOJI) {
            return false;
        }
        if (keyCode == KEY_BACKSPACE) {
            if (SEARCH.length() > 0) {
                SEARCH.setLength(SEARCH.length() - 1);
                refresh();
            }
            return true;
        }
        if ((keyCode == KEY_ENTER || keyCode == KEY_KP_ENTER) && !names.isEmpty()) {
            pick(names.get(0), false, input);
            return true;
        }
        return false;
    }
}
