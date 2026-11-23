package com.wf.wfchat.core;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;

/**
 * Server => client emoji transfer, carried in the insertion of an otherwise empty chat line (no custom packets).
 * <pre>
 * M;ver;idx;total;name;sha256;len;kind     manifest entry; total 0 = no emoji
 * D;ver;idx;part;parts;base64              data chunk of manifest entry idx
 * </pre>
 * Only sent in reply to {@code /wfchat emoji manifest|get}, so vanilla clients never receive it.
 */
public final class EmojiWire {
    public static final String PREFIX = "wfchat:em=";
    /** Raw bytes per data chunk; base64 stays well under NBT's 64 KiB string limit. */
    public static final int CHUNK = 9000;

    private EmojiWire() {
    }

    public static Component message(String payload) {
        return Component.empty().withStyle(Style.EMPTY.withInsertion(PREFIX + payload));
    }

    /** Payload after the prefix, or null when {@code raw} is not an emoji transfer line. */
    @Nullable
    public static String payload(Component raw) {
        String ins = raw.getStyle().getInsertion();
        if (ins == null || !ins.startsWith(PREFIX) || !raw.getSiblings().isEmpty() || !raw.getString().isEmpty()) {
            return null;
        }
        return ins.substring(PREFIX.length());
    }
}
