package com.wf.wfchat.client;

import com.wf.wfchat.WfChat;
import com.wf.wfchat.api.Glyphs;
import com.wf.wfchat.api.VanityProvider;
import com.wf.wfchat.core.ChatTypes;
import com.wf.wfchat.core.Registry;
import com.wf.wfchat.core.Toml;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Applies {@link VanityProvider} tags in front of the sender's name; ships a JSON-file provider. */
final class Vanity {
    private static final String TEMPLATE = """
            # Tags shown before a player's name, by name, UUID or "*" (everyone). Glyph shortcodes (:name:) expand.
            [tags]
            # "*" = ["[WF]"]
            # Notch = [":crown:"]
            """;

    private final Map<String, List<String>> fileTags = new HashMap<>();
    private final List<Component> scratch = new ArrayList<>(4);

    void init() {
        Registry.get().vanity(this::fromFile);
        reloadFile();
    }

    void reloadFile() {
        fileTags.clear();
        Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("wfchat-vanity.toml");
        try {
            Toml.Table tags = Toml.readOrCreate(file, TEMPLATE).table("tags");
            for (String key : tags.keys()) {
                fileTags.put(key.toLowerCase(Locale.ROOT), tags.strings(key));
            }
        } catch (IOException | Toml.ParseException ex) {
            WfChat.LOGGER.warn("wfchat-vanity.toml unusable: {}", ex.toString());
        }
    }

    private void fromFile(UUID id, String name, VanityProvider.TagSink sink) {
        List<String> byId = fileTags.get(id.toString().toLowerCase(Locale.ROOT));
        List<String> byName = fileTags.get(name.toLowerCase(Locale.ROOT));
        List<String> all = fileTags.get("*");
        for (List<String> list : new List[]{all, byId, byName}) {
            if (list != null) {
                for (String t : list) {
                    sink.add(t, 0xFFFFFF);
                }
            }
        }
    }

    Component apply(Component content, PlayerContext ctx) {
        if (ctx == null) {
            return content;
        }
        scratch.clear();
        VanityProvider.TagSink sink = new VanityProvider.TagSink() {
            @Override
            public void add(Component tag) {
                scratch.add(tag);
            }

            @Override
            public void add(String text, int rgb) {
                scratch.add(Glyphs.expand(text, rgb));
            }
        };
        for (VanityProvider p : Registry.get().vanityProviders()) {
            p.tags(ctx.id(), ctx.name(), sink);
        }
        if (scratch.isEmpty()) {
            return content;
        }
        MutableComponent tags = Component.empty();
        for (Component t : scratch) {
            tags.append(t).append(" ");
        }
        TranslatableContents t = ChatTypes.translatable(content);
        if (t != null && t.getArgs().length > 0 && t.getArgs()[0] instanceof Component name) {
            Object[] args = t.getArgs().clone();
            args[0] = Component.empty().append(tags).append(name);
            MutableComponent out = Component.translatableWithFallback(t.getKey(), t.getFallback(), args)
                    .setStyle(content.getStyle());
            content.getSiblings().forEach(out::append);
            return out;
        }
        return Component.empty().append(tags).append(content);
    }
}
