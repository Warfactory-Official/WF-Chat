package com.wf.wfchat.client;

import com.wf.wfchat.WfChat;
import com.wf.wfchat.api.ChannelSink;
import com.wf.wfchat.api.ChannelSpec;
import com.wf.wfchat.api.IncomingMessage;
import com.wf.wfchat.api.Sender;
import com.wf.wfchat.core.Builtin;
import com.wf.wfchat.core.BridgeRule;
import com.wf.wfchat.core.Registry;
import com.wf.wfchat.core.Toml;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * Lines a Discord (or other) bridge mod broadcasts are plain system messages; {@code config/wfchat-bridges.toml}
 * says how to recognise them. Matches get a channel of their own, optionally also staying in Global.
 * Player-signed chat never matches, so a player cannot spoof a bridge line.
 */
final class Bridges {
    private static final String TEMPLATE = """
            # Chat bridges: recognise lines a Discord bridge mod broadcasts into Minecraft chat.
            # pattern = Java regex searched in the line's plain text.
            # mode = "pin": line shows in Global and in the bridge's own channel; "own": only the bridge channel.
            # The default matches DiscordSRV-style "[Discord] ..." / "[Discord | Role] ..." lines. Bridge mods let you
            # change their format; point pattern at whatever tag yours uses.
            [bridge.discord]
            title = "Discord"
            color = "5865F2"
            mode = "pin"
            pattern = '%s'

            # [bridge.relay]
            # title = "Relay"
            # mode = "own"
            # pattern = '^\\[Relay\\]'
            """.formatted(BridgeRule.DEFAULT_PATTERN);

    private final List<BridgeRule> rules = new ArrayList<>();

    void init() {
        Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("wfchat-bridges.toml");
        try {
            List<String> errors = new ArrayList<>();
            rules.addAll(BridgeRule.parse(Toml.readOrCreate(file, TEMPLATE), errors));
            errors.forEach(e -> WfChat.LOGGER.warn("wfchat-bridges.toml: {}", e));
        } catch (IOException | Toml.ParseException ex) {
            WfChat.LOGGER.warn("wfchat-bridges.toml unusable, no bridges: {}", ex.toString());
        }
        Registry.get().classifier(this::classify);
    }

    private void classify(IncomingMessage message, ChannelSink sink) {
        if (sink.claimed() || message.kind() == IncomingMessage.Kind.PLAYER) {
            return;
        }
        String text = message.content().getString();
        for (BridgeRule rule : rules) {
            if (rule.matches(text)) {
                sink.add(ChannelSpec.builder(rule.id()).title(rule.title()).color(rule.color()).order(25).priority(5)
                        .dynamic(true).sender(Sender.CHAT).build());
                if (rule.pin()) {
                    sink.add(Builtin.GLOBAL);
                }
                sink.claim();
                return;
            }
        }
    }
}
