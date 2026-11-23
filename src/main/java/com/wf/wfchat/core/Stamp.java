package com.wf.wfchat.core;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;

/**
 * Channel stamp a WF Chat server hides in an empty first sibling's insertion:
 * {@code wfchat:ch=<channel>[;u=<uuid>;n=<name>]}. Vanilla clients render nothing for it.
 */
public record Stamp(String channel, @Nullable UUID sender, @Nullable String senderName, Component inner) {
    private static final String PREFIX = "wfchat:ch=";

    public static Component write(String channel, @Nullable UUID sender, @Nullable String senderName, Component inner) {
        String data = PREFIX + channel + (sender != null ? ";u=" + sender + ";n=" + senderName : "");
        return Component.empty()
                .append(Component.empty().withStyle(Style.EMPTY.withInsertion(data)))
                .append(inner);
    }

    @Nullable
    public static Stamp read(Component content) {
        var siblings = content.getSiblings();
        if (siblings.size() != 2) {
            return null;
        }
        String data = siblings.get(0).getStyle().getInsertion();
        if (data == null || !data.startsWith(PREFIX)) {
            return null;
        }
        String[] parts = data.substring(PREFIX.length()).split(";");
        UUID sender = null;
        String name = null;
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].startsWith("u=")) {
                sender = UUID.fromString(parts[i].substring(2));
            } else if (parts[i].startsWith("n=")) {
                name = parts[i].substring(2);
            }
        }
        return new Stamp(parts[0], sender, name, siblings.get(1));
    }
}
