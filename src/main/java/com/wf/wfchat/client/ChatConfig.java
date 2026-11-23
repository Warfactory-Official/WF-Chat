package com.wf.wfchat.client;

import com.wf.wfchat.WfChat;
import com.wf.wfchat.core.Toml;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;

/** {@code config/wfchat-chat.toml}: received-message styling switches. */
public final class ChatConfig {
    private static final String TEMPLATE = """
            # WF Chat (client)
            # Markup in received player messages: **bold** ~~strikethrough~~ __underline__ ||spoiler|| &#RRGGBB colour (&r resets).
            # Backslash escapes a marker. Other players see the raw markers unless they run WF Chat.
            [style]
            enabled = true
            colors = true
            formatting = true
            spoilers = true
            """;

    public boolean styleEnabled = true;
    public boolean colors = true;
    public boolean formatting = true;
    public boolean spoilers = true;

    static ChatConfig load() {
        ChatConfig c = new ChatConfig();
        Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("wfchat-chat.toml");
        try {
            Toml.Table s = Toml.readOrCreate(file, TEMPLATE).table("style");
            c.styleEnabled = s.bool("enabled", true);
            c.colors = s.bool("colors", true);
            c.formatting = s.bool("formatting", true);
            c.spoilers = s.bool("spoilers", true);
        } catch (IOException | Toml.ParseException ex) {
            WfChat.LOGGER.warn("wfchat-chat.toml unusable, defaults in effect: {}", ex.toString());
        }
        return c;
    }
}
