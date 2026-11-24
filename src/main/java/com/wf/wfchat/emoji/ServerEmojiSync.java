package com.wf.wfchat.emoji;

import com.wf.wfchat.WfChat;
import com.wf.wfchat.core.EmojiFile;
import com.wf.wfchat.core.EmojiWire;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Client half of {@link EmojiWire}. Everything a server sends is untrusted: names, lengths, hashes and the files
 * themselves are re-verified with the client's own limits, and the file must equal the server's declared SHA-256.
 */
public final class ServerEmojiSync {
    private static final long GET_INTERVAL_MS = 1200;
    private static final long GET_TIMEOUT_MS = 20000;
    private static final int GET_BATCH = 6;
    private static final int GET_BYTES = 262144;
    private static final int MAX_ATTEMPTS = 3;

    private static @Nullable ClientPacketListener connection;
    private static boolean requested;
    private static String version = "";
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final Map<String, EmojiFile.Verified> READY = new LinkedHashMap<>();
    private static List<Integer> outstanding = List.of();
    private static long lastGet;
    private static int getsSent;

    private ServerEmojiSync() {
    }

    private static final class Entry {
        final int index;
        final String name;
        final String sha;
        final int len;
        final EmojiFile.Kind kind;
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        int nextPart;
        int attempts;
        boolean done;

        Entry(int index, String name, String sha, int len, EmojiFile.Kind kind) {
            this.index = index;
            this.name = name;
            this.sha = sha;
            this.len = len;
            this.kind = kind;
        }

        String file() {
            return "server/" + sha + "." + kind.extension;
        }
    }

    /** Once per frame: starts the handshake on a new connection and paces data requests. */
    public static void tick(boolean serverHasMod) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener conn = mc.getConnection();
        if (conn != connection) {
            connection = conn;
            requested = false;
            ENTRIES.clear();
            READY.clear();
            outstanding = List.of();
            version = "";
            getsSent = 0;
            Emojis.get().clearServerEmoji();
        }
        Emojis emojis = Emojis.get();
        if (conn == null || !serverHasMod || !emojis.enabled() || !emojis.config().acceptServer) {
            return;
        }
        if (!requested) {
            requested = true;
            conn.sendCommand("wfchat emoji manifest");
            return;
        }
        long now = System.currentTimeMillis();
        if (ENTRIES.isEmpty() || now - lastGet < GET_INTERVAL_MS) {
            return;
        }
        if (!outstanding.isEmpty()) {
            if (now - lastGet < GET_TIMEOUT_MS) {
                return;
            }
            for (int i : outstanding) {
                Entry e = ENTRIES.get(i);
                e.data.reset();
                e.nextPart = 0;
            }
        }
        List<Integer> batch = new ArrayList<>();
        int bytes = 0;
        for (Entry e : ENTRIES) {
            if (!e.done && e.attempts < MAX_ATTEMPTS && batch.size() < GET_BATCH && (batch.isEmpty() || bytes + e.len <= GET_BYTES)) {
                batch.add(e.index);
                bytes += e.len;
                e.attempts++;
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        outstanding = batch;
        lastGet = now;
        getsSent++;
        StringBuilder cmd = new StringBuilder("wfchat emoji get ").append(version);
        batch.forEach(i -> cmd.append(' ').append(i));
        conn.sendCommand(cmd.toString());
    }

    /** Data requests sent on the current connection. */
    public static int getsSent() {
        return getsSent;
    }

    /** Emoji from the current server that are verified and usable. */
    public static int readyCount() {
        return READY.size();
    }

    /** True when {@code raw} was an emoji transfer line (consumed, not chat). */
    public static boolean consume(Component raw) {
        String payload = EmojiWire.payload(raw);
        if (payload == null) {
            return false;
        }
        Emojis emojis = Emojis.get();
        if (!emojis.enabled() || !emojis.config().acceptServer || !requested) {
            return true;
        }
        try {
            String[] f = payload.split(";", -1);
            if (f[0].equals("M") && f.length == 8) {
                manifest(f);
            } else if (f[0].equals("D") && f.length == 6) {
                data(f);
            }
        } catch (RuntimeException | EmojiFile.Rejected | IOException ex) {
            WfChat.LOGGER.warn("server emoji message rejected: {}", ex.toString());
        }
        return true;
    }

    private static void manifest(String[] f) throws EmojiFile.Rejected, IOException {
        EmojiConfig cfg = Emojis.get().config();
        int idx = Integer.parseInt(f[2]);
        int total = Integer.parseInt(f[3]);
        if (total < 0 || total > cfg.serverMaxCount || idx < 0 || idx >= Math.max(total, 1)) {
            throw new EmojiFile.Rejected("manifest count " + total + " outside max_count " + cfg.serverMaxCount);
        }
        if (!f[1].equals(version)) {
            ENTRIES.clear();
            READY.clear();
            outstanding = List.of();
            version = f[1];
        }
        if (total == 0) {
            return;
        }
        String name = f[4];
        String sha = f[5];
        int len = Integer.parseInt(f[6]);
        EmojiFile.Kind kind = switch (f[7]) {
            case "png" -> EmojiFile.Kind.PNG;
            case "gif" -> EmojiFile.Kind.GIF;
            case "webp" -> EmojiFile.Kind.WEBP;
            default -> throw new EmojiFile.Rejected("unknown kind " + f[7]);
        };
        if (!EmojiFile.validName(name) || !sha.matches("[0-9a-f]{64}") || len <= 0 || len > cfg.serverLimits.maxBytes()) {
            throw new EmojiFile.Rejected("manifest entry " + idx + " (" + name + ") invalid");
        }
        if (idx != ENTRIES.size()) {
            throw new EmojiFile.Rejected("manifest out of order at " + idx);
        }
        Entry e = new Entry(idx, name, sha, len, kind);
        ENTRIES.add(e);
        Path cached = EmojiConfig.dir().resolve(e.file());
        if (Files.isRegularFile(cached)) {
            byte[] bytes = Files.readAllBytes(cached);
            Files.setLastModifiedTime(cached, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            try {
                accept(e, bytes);
            } catch (EmojiFile.Rejected ex) {
                Files.deleteIfExists(cached);
            }
        }
        if (idx == total - 1) {
            publish();
        }
    }

    private static void data(String[] f) throws EmojiFile.Rejected, IOException {
        if (!f[1].equals(version)) {
            return;
        }
        int idx = Integer.parseInt(f[2]);
        int part = Integer.parseInt(f[3]);
        int parts = Integer.parseInt(f[4]);
        if (idx < 0 || idx >= ENTRIES.size() || !outstanding.contains(idx)) {
            return;
        }
        Entry e = ENTRIES.get(idx);
        if (e.done || part != e.nextPart || parts != (e.len + EmojiWire.CHUNK - 1) / EmojiWire.CHUNK || part >= parts) {
            return;
        }
        byte[] chunk = Base64.getDecoder().decode(f[5]);
        if (e.data.size() + chunk.length > e.len) {
            e.data.reset();
            e.nextPart = 0;
            throw new EmojiFile.Rejected(e.name + " longer than declared");
        }
        e.data.write(chunk);
        e.nextPart++;
        if (e.nextPart == parts) {
            byte[] all = e.data.toByteArray();
            e.data.reset();
            Path file = EmojiConfig.dir().resolve(e.file());
            accept(e, all);
            Files.createDirectories(file.getParent());
            Files.write(file, all);
            outstanding = outstanding.stream().filter(i -> i != idx).toList();
            publish();
        }
    }

    private static void accept(Entry e, byte[] bytes) throws EmojiFile.Rejected {
        if (bytes.length != e.len || !EmojiFile.sha256(bytes).equals(e.sha)) {
            throw new EmojiFile.Rejected(e.name + " does not match its declared hash");
        }
        EmojiFile.Verified v = EmojiFile.verify(e.name + "." + e.kind.extension, bytes, Emojis.get().config().serverLimits);
        if (!v.sha256().equals(e.sha)) {
            throw new EmojiFile.Rejected(e.name + " carries metadata the server should have stripped");
        }
        e.done = true;
        READY.put(e.name, v);
    }

    private static void publish() {
        Emojis.get().setServerEmoji(READY);
    }
}
