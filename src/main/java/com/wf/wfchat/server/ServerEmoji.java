package com.wf.wfchat.server;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.wf.wfchat.WfChat;
import com.wf.wfchat.core.EmojiFile;
import com.wf.wfchat.core.EmojiWire;
import com.wf.wfchat.core.Toml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-provided emoji: {@code config/wfchat/emoji/<name>.png|gif|webp}, verified at load by {@link EmojiFile} with the
 * limits of {@code config/wfchat-server.toml}; rejects are logged and listed by {@code /wfchat emoji reload}.
 */
public final class ServerEmoji {
    private static final String TEMPLATE = """
            # WF Chat (server)
            # Emoji files live in config/wfchat/emoji/<name>.png, .gif or .webp, name = [a-z0-9_]{2,32}.
            # Files are verified (type, size, pixels, metadata); metadata is stripped; failures are skipped.
            [emoji]
            enabled = true
            max_count = 256
            max_bytes = 131072
            min_px = 8
            max_px = 128
            max_frames = 128
            """;
    private static final int MAX_GET = 8;
    private static final long GET_COOLDOWN_MS = 500;
    private static final long MANIFEST_COOLDOWN_MS = 2000;

    private static volatile Pack pack;
    private static final Map<UUID, Long> LAST_GET = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_MANIFEST = new ConcurrentHashMap<>();

    private ServerEmoji() {
    }

    private record Pack(String version, List<EmojiFile.Verified> emoji, List<String> rejected) {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("emoji")
                .then(Commands.literal("list").executes(ctx -> list(ctx)))
                .then(Commands.literal("reload").requires(ServerEmoji::isAdmin).executes(ctx -> reload(ctx)))
                .then(Commands.literal("manifest").executes(ctx -> manifest(ctx)))
                .then(Commands.literal("get").then(Commands.argument("request", StringArgumentType.greedyString())
                        .executes(ctx -> get(ctx))));
    }

    private static boolean isAdmin(CommandSourceStack src) {
        //? if <26.1 {
        return src.hasPermission(3);
        //?} else {
        /*return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(src);
        *///?}
    }

    private static Pack pack() {
        Pack p = pack;
        if (p == null) {
            synchronized (ServerEmoji.class) {
                p = pack;
                if (p == null) {
                    p = pack = load();
                }
            }
        }
        return p;
    }

    private static Pack load() {
        Path dir = Path.of("config", "wfchat", "emoji");
        List<String> rejected = new ArrayList<>();
        List<EmojiFile.Verified> ok = new ArrayList<>();
        try {
            Toml.Table t = Toml.readOrCreate(Path.of("config", "wfchat-server.toml"), TEMPLATE).table("emoji");
            if (!t.bool("enabled", true)) {
                return new Pack(version(ok), ok, rejected);
            }
            EmojiFile.Limits limits = new EmojiFile.Limits(t.integer("max_bytes", 1, 1 << 20, 131072),
                    t.integer("min_px", 1, 1024, 8), t.integer("max_px", 1, 1024, 128),
                    t.integer("max_frames", 1, 256, 128), true);
            int maxCount = t.integer("max_count", 0, 4096, 256);
            Files.createDirectories(dir);
            List<Path> files;
            try (Stream<Path> s = Files.list(dir)) {
                files = s.filter(Files::isRegularFile).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            }
            for (Path f : files) {
                String fileName = f.getFileName().toString();
                try {
                    if (ok.size() >= maxCount) {
                        throw new EmojiFile.Rejected("over max_count " + maxCount);
                    }
                    if (Files.size(f) > limits.maxBytes()) {
                        throw new EmojiFile.Rejected("file is " + Files.size(f) + " bytes, limit " + limits.maxBytes());
                    }
                    EmojiFile.Verified v = EmojiFile.verify(fileName, Files.readAllBytes(f), limits);
                    if (ok.stream().anyMatch(o -> o.name().equals(v.name()))) {
                        throw new EmojiFile.Rejected("duplicate name " + v.name());
                    }
                    ok.add(v);
                    if (v.strippedBytes() > 0) {
                        WfChat.LOGGER.info("emoji {}: stripped {} bytes of metadata", fileName, v.strippedBytes());
                    }
                } catch (EmojiFile.Rejected ex) {
                    rejected.add(fileName + ": " + ex.getMessage());
                    WfChat.LOGGER.warn("emoji {} rejected: {}", fileName, ex.getMessage());
                }
            }
        } catch (IOException | Toml.ParseException ex) {
            WfChat.LOGGER.error("wfchat server emoji unavailable: {}", ex.toString());
        }
        ok.sort(Comparator.comparing(EmojiFile.Verified::name));
        return new Pack(version(ok), List.copyOf(ok), List.copyOf(rejected));
    }

    private static String version(List<EmojiFile.Verified> emoji) {
        StringBuilder sb = new StringBuilder();
        for (EmojiFile.Verified v : emoji) {
            sb.append(v.name()).append(':').append(v.sha256()).append('\n');
        }
        return EmojiFile.sha256(sb.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 8);
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        Pack p = pack();
        ctx.getSource().sendSuccess(() -> Component.literal(p.emoji.size() + " server emoji: "
                + String.join(", ", p.emoji.stream().map(EmojiFile.Verified::name).toList())), false);
        return p.emoji.size();
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        Pack p;
        synchronized (ServerEmoji.class) {
            p = pack = load();
        }
        Pack loaded = p;
        ctx.getSource().sendSuccess(() -> Component.literal("Loaded " + loaded.emoji.size() + " emoji, "
                + loaded.rejected.size() + " rejected"), true);
        for (String r : p.rejected) {
            ctx.getSource().sendFailure(Component.literal(r));
        }
        return p.emoji.size();
    }

    private static boolean throttled(Map<UUID, Long> last, ServerPlayer player, long cooldown) {
        long now = System.currentTimeMillis();
        if (last.size() > 1024) {
            last.clear();
        }
        Long prev = last.put(player.getUUID(), now);
        return prev != null && now - prev < cooldown;
    }

    private static int manifest(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player) || throttled(LAST_MANIFEST, player, MANIFEST_COOLDOWN_MS)) {
            return 0;
        }
        Pack p = pack();
        int total = p.emoji.size();
        if (total == 0) {
            player.sendSystemMessage(EmojiWire.message("M;" + p.version + ";0;0;-;-;0;-"));
            return 0;
        }
        for (int i = 0; i < total; i++) {
            EmojiFile.Verified v = p.emoji.get(i);
            player.sendSystemMessage(EmojiWire.message("M;" + p.version + ";" + i + ";" + total + ";" + v.name() + ";"
                    + v.sha256() + ";" + v.data().length + ";" + v.kind().extension));
        }
        return total;
    }

    private static int get(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player) || throttled(LAST_GET, player, GET_COOLDOWN_MS)) {
            return 0;
        }
        String[] parts = StringArgumentType.getString(ctx, "request").trim().split("\\s+");
        Pack p = pack();
        if (parts.length < 2 || !parts[0].equals(p.version)) {
            return manifest(ctx);
        }
        int sent = 0;
        for (int k = 1; k < parts.length && k <= MAX_GET; k++) {
            int idx;
            try {
                idx = Integer.parseInt(parts[k]);
            } catch (NumberFormatException ex) {
                continue;
            }
            if (idx < 0 || idx >= p.emoji.size()) {
                continue;
            }
            byte[] data = p.emoji.get(idx).data();
            int chunks = (data.length + EmojiWire.CHUNK - 1) / EmojiWire.CHUNK;
            for (int c = 0; c < chunks; c++) {
                int from = c * EmojiWire.CHUNK;
                int to = Math.min(data.length, from + EmojiWire.CHUNK);
                player.sendSystemMessage(EmojiWire.message("D;" + p.version + ";" + idx + ";" + c + ";" + chunks + ";"
                        + Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(data, from, to))));
            }
            sent++;
        }
        return sent;
    }
}
