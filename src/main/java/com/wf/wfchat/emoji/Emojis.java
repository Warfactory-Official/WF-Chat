package com.wf.wfchat.emoji;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.wf.wfchat.WfChat;
import com.wf.wfchat.core.CachePrune;
import com.wf.wfchat.core.EmojiFile;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * Emoji registry: names, aliases and Unicode sequences resolve to {@link EmojiRef}s, each owning one
 * placeholder character of the {@code wfchat:emoji} font (U+E000..U+E7FF, all 9 px wide).
 */
public final class Emojis {
    public static final String FONT = "wfchat:emoji";
    static final char FIRST = '';
    static final int CAPACITY = 0x800;

    private static final Emojis INSTANCE = new Emojis();

    private final EmojiRef[] byChar = new EmojiRef[CAPACITY];
    private final Map<String, EmojiRef> byKey = new HashMap<>();
    private int next;
    private volatile Map<String, EmojiRef> server = Map.of();
    private volatile Dictionary dictionary = Dictionary.EMPTY;
    private EmojiConfig config = new EmojiConfig();
    private boolean started;

    private Emojis() {
    }

    public static Emojis get() {
        return INSTANCE;
    }

    /** Immutable lookup tables, swapped whole when the dictionary finishes loading. */
    record Dictionary(Map<String, String> nameToUtf, Map<String, String> utfToName, Map<String, String> full,
                      List<String> names, Map<String, List<String>> categories, int maxCodepoints) {
        static final Dictionary EMPTY = new Dictionary(Map.of(), Map.of(), Map.of(), List.of(), Map.of(), 1);
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        config = EmojiConfig.load();
        if (config.enabled) {
            EmojiImages.get().executor().execute(this::pruneCache);
            EmojiImages.get().executor().execute(this::loadDictionary);
        }
    }

    public boolean enabled() {
        return config.enabled;
    }

    public boolean animate() {
        return config.animate;
    }

    EmojiConfig config() {
        return config;
    }

    private void pruneCache() {
        try {
            long freed = CachePrune.prune(EmojiConfig.dir(), config.cacheMaxMb * 1024L * 1024L, Set.of("dictionary.json"));
            if (freed > 0) {
                WfChat.LOGGER.info("emoji cache: freed {} KiB", freed / 1024);
            }
        } catch (IOException ex) {
            WfChat.LOGGER.warn("emoji cache prune failed: {}", ex.toString());
        }
    }

    private void loadDictionary() {
        try {
            Path cache = EmojiConfig.dir().resolve("dictionary.json");
            byte[] data;
            if (Files.isRegularFile(cache)) {
                data = Files.readAllBytes(cache);
            } else {
                data = EmojiImages.get().fetch(config.dictionary);
                Files.createDirectories(cache.getParent());
                Files.write(cache, data);
            }
            parse(new String(data, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException | InterruptedException ex) {
            WfChat.LOGGER.warn("emoji dictionary unavailable: {}", ex.toString());
        }
    }

    private void parse(String json) {
        Map<String, String> nameToUtf = new HashMap<>();
        Map<String, String> utfToName = new HashMap<>();
        Map<String, String> full = new HashMap<>();
        List<String> names = new ArrayList<>();
        Map<String, List<String>> categories = new java.util.LinkedHashMap<>();
        int max = 1;
        JsonArray all = JsonParser.parseString(json).getAsJsonArray();
        for (JsonElement e : all) {
            var o = e.getAsJsonObject();
            if (!o.has("emoji")) {
                continue;
            }
            String utf = key(o.get("emoji").getAsString());
            full.put(utf, o.get("emoji").getAsString());
            max = Math.max(max, utf.codePointCount(0, utf.length()));
            boolean first = true;
            for (JsonElement a : o.getAsJsonArray("aliases")) {
                String name = a.getAsString().toLowerCase(Locale.ROOT);
                nameToUtf.put(name, utf);
                names.add(name);
                if (first) {
                    categories.computeIfAbsent(o.has("category") ? o.get("category").getAsString() : "Other",
                            k -> new ArrayList<>()).add(name);
                    utfToName.put(utf, name);
                    first = false;
                }
            }
        }
        dictionary = new Dictionary(nameToUtf, utfToName, full, names, categories, max);
        WfChat.LOGGER.info("emoji dictionary: {} names", names.size());
    }

    /** Variation selector 16 carries no identity. */
    static String key(String utf) {
        return utf.replace("️", "");
    }

    Dictionary dictionary() {
        return dictionary;
    }

    @Nullable
    public EmojiRef byChar(char c) {
        int i = c - FIRST;
        return i >= 0 && i < CAPACITY ? byChar[i] : null;
    }

    @Nullable
    public synchronized EmojiRef standardByUtf(String utfKey) {
        String name = dictionary.utfToName.getOrDefault(utfKey, utfKey);
        String file = twemojiFile(utfKey.indexOf('\u200d') >= 0 ? dictionary.full.getOrDefault(utfKey, utfKey) : utfKey);
        return create("u:" + utfKey, name, file + ".png", config.images + file + ".png",
                EmojiConfig.REMOTE);
    }

    @Nullable
    public EmojiRef standardByName(String name) {
        String utf = dictionary.nameToUtf.get(name.toLowerCase(Locale.ROOT));
        return utf == null ? null : standardByUtf(utf);
    }

    @Nullable
    public synchronized EmojiRef discord(long id, String name, boolean animated) {
        String ext = animated ? "gif" : "png";
        return create("d:" + id + ext, name, "discord-" + id + "." + ext,
                "https://cdn.discordapp.com/emojis/" + id + "." + ext + "?size=64", EmojiConfig.REMOTE);
    }

    @Nullable
    public EmojiRef custom(String name) {
        EmojiConfig.Custom c = config.custom.get(name.toLowerCase(Locale.ROOT));
        return c == null ? null : discord(c.id(), name, c.animated());
    }

    @Nullable
    EmojiConfig.Custom customId(String name) {
        return config.custom.get(name.toLowerCase(Locale.ROOT));
    }

    /** Emoji the connected server provides, by name; replaced whole on every sync. */
    public synchronized void setServerEmoji(Map<String, EmojiFile.Verified> verified) {
        Map<String, EmojiRef> map = new ConcurrentHashMap<>();
        for (Map.Entry<String, EmojiFile.Verified> e : verified.entrySet()) {
            EmojiFile.Verified v = e.getValue();
            EmojiRef ref = create("s:" + v.sha256(), e.getKey(), "server/" + v.sha256() + "." + v.kind().extension,
                    null, config.serverLimits);
            if (ref != null) {
                map.put(e.getKey(), ref);
            }
        }
        server = map;
    }

    public void clearServerEmoji() {
        server = Map.of();
    }


    @Nullable
    private EmojiRef create(String key, String name, String file, @Nullable String url, EmojiFile.Limits limits) {
        EmojiRef r = byKey.get(key);
        if (r != null) {
            return r;
        }
        if (next >= CAPACITY) {
            return null;
        }
        r = new EmojiRef((char) (FIRST + next), name, file, url, limits);
        byChar[next++] = r;
        byKey.put(key, r);
        return r;
    }

    private static String twemojiFile(String utf) {
        StringBuilder sb = new StringBuilder();
        utf.codePoints().forEach(cp -> sb.append(sb.length() == 0 ? "" : "-").append(Integer.toHexString(cp)));
        return sb.toString();
    }

    /** Server and configured emoji, the picker's "Custom" category. */
    public List<String> customNames() {
        List<String> out = new ArrayList<>(server.keySet().stream().sorted().toList());
        config.custom.keySet().stream().sorted().filter(n -> !out.contains(n)).forEach(out::add);
        return out;
    }

    /** Standard emoji names by gemoji category, in dictionary order. */
    public Map<String, List<String>> categories() {
        return dictionary.categories;
    }

    /** Every name containing {@code query} (aliases included); custom first, prefix matches before substring matches. */
    public List<String> search(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String n : customNames()) {
            if (n.contains(q)) {
                out.add(n);
            }
        }
        for (String n : dictionary.names) {
            if (n.startsWith(q) && !out.contains(n)) {
                out.add(n);
            }
        }
        for (String n : dictionary.names) {
            if (n.contains(q) && !out.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }

    /** Names starting with, then containing, {@code query}; custom names first. */
    public List<String> suggest(String query, int limit) {
        String q = query.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String n : server.keySet()) {
            if (n.startsWith(q) && out.size() < limit) {
                out.add(n);
            }
        }
        for (String n : config.custom.keySet()) {
            if (n.startsWith(q) && out.size() < limit && !out.contains(n)) {
                out.add(n);
            }
        }
        for (String n : dictionary.names) {
            if (out.size() >= limit) {
                return out;
            }
            if (n.startsWith(q) && !out.contains(n)) {
                out.add(n);
            }
        }
        for (String n : dictionary.names) {
            if (out.size() >= limit) {
                break;
            }
            if (n.contains(q) && !out.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }

    @Nullable
    public EmojiRef byName(String name) {
        EmojiRef s = server.get(name.toLowerCase(Locale.ROOT));
        if (s != null) {
            return s;
        }
        EmojiRef c = custom(name);
        return c != null ? c : standardByName(name);
    }
}
