package com.wf.wfchat.emoji;

import com.wf.wfchat.WfChat;
import com.wf.wfchat.core.EmojiFile;
import com.wf.wfchat.core.Toml;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;

/** {@code config/wfchat-emoji.toml}. */
final class EmojiConfig {
    static final String DEFAULT_DICTIONARY = "https://raw.githubusercontent.com/github/gemoji/master/db/emoji.json";
    static final String DEFAULT_IMAGES = "https://cdn.jsdelivr.net/gh/jdecked/twemoji@15.1.0/assets/72x72/";
    /** Images from Twemoji/Discord CDNs: size capped, shape free. */
    static final EmojiFile.Limits REMOTE = new EmojiFile.Limits(1 << 20, 1, 1024, 256, false);

    private static final String TEMPLATE = """
            # WF Chat emoji (client)
            enabled = true
            # animated GIF emoji play; false shows the first frame
            animate = true
            # downloaded emoji images kept on disk; least recently used are deleted at startup
            cache_max_mb = 128
            dictionary = "%s"
            images = "%s"

            # Emoji files WF Chat servers send. Each is verified (type, size, pixels, metadata) before use.
            [server_emoji]
            accept = true
            max_count = 512
            max_bytes = 131072
            min_px = 8
            max_px = 128
            max_frames = 128

            # Discord emoji by name: pepe = "123456789012345678", animated ones: dance = "a:123456789012345678"
            [custom]
            """.formatted(DEFAULT_DICTIONARY, DEFAULT_IMAGES);

    record Custom(long id, boolean animated) {
    }

    boolean enabled = true;
    boolean animate = true;
    int cacheMaxMb = 128;
    String dictionary = DEFAULT_DICTIONARY;
    String images = DEFAULT_IMAGES;
    boolean acceptServer = true;
    int serverMaxCount = 512;
    EmojiFile.Limits serverLimits = EmojiFile.Limits.DEFAULT;
    final Map<String, Custom> custom = new HashMap<>();

    static Path dir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("wfchat").resolve("emoji");
    }

    static EmojiConfig load() {
        EmojiConfig c = new EmojiConfig();
        Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("wfchat-emoji.toml");
        try {
            Toml.Table t = Toml.readOrCreate(file, TEMPLATE);
            c.enabled = t.bool("enabled", true);
            c.animate = t.bool("animate", true);
            c.cacheMaxMb = t.integer("cache_max_mb", 1, 100000, 128);
            c.dictionary = t.string("dictionary", DEFAULT_DICTIONARY);
            c.images = t.string("images", DEFAULT_IMAGES);
            Toml.Table s = t.table("server_emoji");
            c.acceptServer = s.bool("accept", true);
            c.serverMaxCount = s.integer("max_count", 0, 4096, 512);
            c.serverLimits = new EmojiFile.Limits(s.integer("max_bytes", 1, 1 << 20, 131072),
                    s.integer("min_px", 1, 1024, 8), s.integer("max_px", 1, 1024, 128),
                    s.integer("max_frames", 1, 256, 128), true);
            Toml.Table custom = t.table("custom");
            for (String name : custom.keys()) {
                String v = custom.string(name, "");
                boolean animated = v.startsWith("a:");
                try {
                    c.custom.put(name.toLowerCase(Locale.ROOT), new Custom(Long.parseLong(animated ? v.substring(2) : v), animated));
                } catch (NumberFormatException ex) {
                    WfChat.LOGGER.warn("wfchat-emoji.toml: custom.{} is not a Discord id", name);
                }
            }
        } catch (IOException | Toml.ParseException ex) {
            WfChat.LOGGER.warn("wfchat-emoji.toml unusable, defaults in effect: {}", ex.toString());
        }
        return c;
    }
}
