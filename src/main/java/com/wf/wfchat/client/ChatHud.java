package com.wf.wfchat.client;

import com.wf.gemrender.ui.Canvas;
import com.wf.wfchat.emoji.EmojiImages;

import com.wf.wfchat.emoji.EmojiRef;
import com.wf.wfchat.emoji.Emojis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.ChatVisiblity;
import org.jetbrains.annotations.Nullable;

/** Draws the chat history through GemRender's Canvas; geometry mirrors vanilla so mods positioning around it keep working. */
public final class ChatHud {
    private static final ButtonBar BUTTONS = new ButtonBar();

    private ChatHud() {
    }

    public static ButtonBar buttons() {
        return BUTTONS;
    }

    private static double timeFactor(int age) {
        double d = 1.0 - (double) age / 200.0;
        d = Mth.clamp(d * 10.0, 0.0, 1.0);
        return d * d;
    }

    private static int lineHeight(Options o) {
        return (int) (9.0 * (o.chatLineSpacing().get() + 1.0));
    }

    private static int visibleLines(Options o, boolean focused) {
        int h = ChatComponent.getHeight(focused ? o.chatHeightFocused().get() : o.chatHeightUnfocused().get());
        return h / lineHeight(o);
    }

    private static int textWidth(Options o) {
        return Mth.floor((float) ChatComponent.getWidth(o.chatWidth().get()) / o.chatScale().get().floatValue());
    }

    public static int linesPerPage(boolean focused) {
        return visibleLines(Minecraft.getInstance().options, focused);
    }

    public static void render(GuiGraphics graphics, int tickCount, int mouseX, int mouseY, boolean focused) {
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;
        ClientChat chat = ClientChat.get();
        chat.sync();
        boolean hidden = o.chatVisibility().get() == ChatVisiblity.HIDDEN;
        Canvas canvas = Canvas.shared();
        if (hidden && !focused) {
            return;
        }
        canvas.begin(graphics);
        if (!hidden) {
            history(canvas, graphics.guiHeight(), tickCount, focused, o, chat);
        }
        if (focused) {
            BUTTONS.render(canvas, graphics.guiWidth(), graphics.guiHeight(), mouseX, mouseY, chat);
            if (mc.screen instanceof ChatScreenAccess access) {
                EmojiSuggest.render(canvas, access.wfchat$input(), graphics.guiHeight(), mouseX, mouseY,
                        graphics.guiHeight() - 28);
            }
        }
        canvas.end();
    }

    private static void history(Canvas c, int guiHeight, int tickCount, boolean focused, Options o, ClientChat chat) {
        EmojiImages.get().pump();
        ChatView view = chat.view;
        int width = textWidth(o);
        view.ensure(width, c.fontVersion());
        int count = view.count();
        if (count == 0) {
            return;
        }
        float scale = o.chatScale().get().floatValue();
        int visible = visibleLines(o, focused);
        int lh = lineHeight(o);
        double spacing = o.chatLineSpacing().get();
        int textOffset = (int) Math.round(-8.0 * (spacing + 1.0) + 4.0 * spacing);
        double textOpacity = o.chatOpacity().get() * 0.9F + 0.1F;
        double bgOpacity = o.textBackgroundOpacity().get();
        int bottom = Mth.floor((float) (guiHeight - 40) / scale);
        c.push().scale(scale).translate(4, 0);
        int drawn = 0;
        int scroll = view.scroll();
        for (int j = 0; j < visible; j++) {
            int idx = count - 1 - (j + scroll);
            if (idx < 0) {
                break;
            }
            int age = tickCount - view.tick(idx);
            if (age >= 200 && !focused) {
                continue;
            }
            double tf = focused ? 1.0 : timeFactor(age);
            int ta = (int) (255.0 * tf * textOpacity);
            int ba = (int) (255.0 * tf * bgOpacity);
            drawn++;
            if (ta <= 3) {
                continue;
            }
            int lineBottom = bottom - j * lh;
            c.fill(-4, lineBottom - lh, width + 8, lineBottom, ba << 24);
            c.text(view.line(idx), 0, lineBottom + textOffset, 0xFFFFFF | ta << 24, true);
            ChatView.LineEmoji em = view.emojis(idx);
            if (em != null) {
                for (int k = 0; k < em.x.length; k++) {
                    drawEmoji(c, em.ref[k], em.x[k], lineBottom + textOffset, ta);
                }
                for (int k = 0; k < em.decoKind.length; k++) {
                    float y = lineBottom + textOffset + (em.decoKind[k] == 1 ? 8 : 4);
                    c.fill(em.decoFrom[k], y, em.decoTo[k], y + 1, em.decoRgb[k] | ta << 24);
                }
                for (int k = 0; k < em.spoilerId.length; k++) {
                    if (!chat.spoilerRevealed(em.spoilerId[k])) {
                        c.fill(em.spoilerFrom[k], lineBottom - lh, em.spoilerTo[k], lineBottom, 0x2A2A2A | ta << 24);
                    }
                }
            }
        }
        if (focused && count * lh != drawn * lh) {
            int total = count * lh;
            int shown = drawn * lh;
            int offset = scroll * shown / count - bottom;
            int bar = shown * shown / total;
            int alpha = offset > 0 ? 170 : 96;
            int tint = view.newSinceScroll() ? 0xCC3333 : 0x33333A;
            int x = width + 4;
            c.fill(x, -offset - bar, x + 2, -offset, tint | alpha << 24);
            c.fill(x + 1, -offset - bar, x + 2, -offset, 0xCCCCCC | alpha << 24);
        }
        c.pop();
    }

    public static void drawEmoji(Canvas c, EmojiRef ref, float x, float y, int alpha) {
        if (ref.state() == EmojiRef.NONE) {
            EmojiImages.get().request(ref);
        }
        var tex = ref.texture();
        if (ref.state() == EmojiRef.READY && tex != null) {
            int frames = ref.frames();
            if (frames <= 1) {
                c.sprite(tex, x, y - 0.5f, x + 9, y + 8.5f, 0xFFFFFF | alpha << 24);
            } else {
                int frame = Emojis.get().animate() ? ref.frameAt(System.currentTimeMillis()) : 0;
                float inset = 0.5f / (36f * frames);
                c.sprite(tex, x, y - 0.5f, x + 9, y + 8.5f, 0f, (float) frame / frames + inset,
                        1f, (float) (frame + 1) / frames - inset, 0xFFFFFF | alpha << 24);
            }
        } else {
            c.frame(x + 1, y, x + 8, y + 7, 1, 0x888888 | alpha / 2 << 24);
        }
    }

    /** Toggles the spoiler under the pointer; true when one was hit. */
    public static boolean clickSpoiler(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;
        ClientChat chat = ClientChat.get();
        ChatView view = chat.view;
        float scale = o.chatScale().get().floatValue();
        double x = mouseX / scale - 4.0;
        double y = (mc.getWindow().getGuiScaledHeight() - mouseY - 40.0) / scale;
        int lh = lineHeight(o);
        if (x < 0 || x > textWidth(o) || y < 0) {
            return false;
        }
        int j = Mth.floor(y / lh);
        int idx = view.count() - 1 - (j + view.scroll());
        if (j >= visibleLines(o, true) || idx < 0 || idx >= view.count()) {
            return false;
        }
        ChatView.LineEmoji em = view.emojis(idx);
        if (em == null) {
            return false;
        }
        for (int k = 0; k < em.spoilerId.length; k++) {
            if (x >= em.spoilerFrom[k] && x < em.spoilerTo[k]) {
                chat.toggleSpoiler(em.spoilerId[k]);
                return true;
            }
        }
        return false;
    }

    @Nullable
    public static Style styleAt(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;
        ChatView view = ClientChat.get().view;
        float scale = o.chatScale().get().floatValue();
        double x = mouseX / scale - 4.0;
        double y = (mc.getWindow().getGuiScaledHeight() - mouseY - 40.0) / scale;
        int lh = lineHeight(o);
        if (x < 0 || x > textWidth(o) || y < 0) {
            return null;
        }
        int j = Mth.floor(y / lh);
        int idx = view.count() - 1 - (j + view.scroll());
        if (j >= visibleLines(o, true) || idx < 0 || idx >= view.count()) {
            return null;
        }
        //? if <26.1 {
        return mc.font.getSplitter().componentStyleAtWidth(view.line(idx), Mth.floor(x));
        //?} else {
        /*return null;
        *///?}
    }
}
