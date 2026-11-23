package com.wf.wfchat.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * TOML subset: {@code [a.b]} tables, bare/quoted keys, basic/literal strings, integers, floats, booleans,
 * arrays (multi-line, trailing comma). Inline tables, dates, multi-line strings, dotted keys, {@code [[arrays]]}
 * => {@link ParseException}.
 */
public final class Toml {
    private Toml() {
    }

    public static final class ParseException extends Exception {
        public ParseException(int line, String message) {
            super("line " + line + ": " + message);
        }
    }

    /** Read-only view of a parsed table with typed, defaulted getters; wrong type => default. */
    public static final class Table {
        private final Map<String, Object> values;

        Table(Map<String, Object> values) {
            this.values = values;
        }

        public static final Table EMPTY = new Table(Map.of());

        public boolean bool(String key, boolean def) {
            return values.get(key) instanceof Boolean b ? b : def;
        }

        public long integer(String key, long def) {
            return values.get(key) instanceof Long l ? l : def;
        }

        public int integer(String key, int min, int max, int def) {
            return (int) Math.max(min, Math.min(max, integer(key, def)));
        }

        public String string(String key, String def) {
            return values.get(key) instanceof String s ? s : def;
        }

        public List<String> strings(String key) {
            List<String> out = new ArrayList<>();
            if (values.get(key) instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof String s) {
                        out.add(s);
                    }
                }
            }
            return out;
        }

        public Table table(String key) {
            return values.get(key) instanceof Map<?, ?> m ? new Table(cast(m)) : EMPTY;
        }

        public Iterable<String> keys() {
            return values.keySet();
        }

        public @Nullable Object raw(String key) {
            return values.get(key);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> cast(Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
    }

    public static Table read(Path file) throws IOException, ParseException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Reads {@code file}, writing {@code defaults} first when it does not exist. */
    public static Table readOrCreate(Path file, String defaults) throws IOException, ParseException {
        if (!Files.isRegularFile(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, defaults, StandardCharsets.UTF_8);
        }
        return read(file);
    }

    public static Table parse(String text) throws ParseException {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        for (int ln = 0; ln < lines.length; ln++) {
            String line = stripComment(lines[ln]).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[[")) {
                throw new ParseException(ln + 1, "array of tables unsupported");
            }
            if (line.startsWith("[")) {
                if (!line.endsWith("]")) {
                    throw new ParseException(ln + 1, "unterminated table header");
                }
                current = root;
                for (String part : splitPath(line.substring(1, line.length() - 1), ln + 1)) {
                    Object next = current.get(part);
                    if (next == null) {
                        next = new LinkedHashMap<String, Object>();
                        current.put(part, next);
                    }
                    if (!(next instanceof Map<?, ?>)) {
                        throw new ParseException(ln + 1, "'" + part + "' is not a table");
                    }
                    current = Table.cast((Map<?, ?>) next);
                }
                continue;
            }
            int eq = indexOfEquals(line);
            if (eq < 0) {
                throw new ParseException(ln + 1, "expected key = value");
            }
            String key = parseKey(line.substring(0, eq).strip(), ln + 1);
            if (current.containsKey(key)) {
                throw new ParseException(ln + 1, "duplicate key '" + key + "'");
            }
            String rest = line.substring(eq + 1).strip();
            if (rest.startsWith("[")) {
                StringBuilder sb = new StringBuilder(rest);
                while (depth(sb) > 0) {
                    if (++ln >= lines.length) {
                        throw new ParseException(ln, "unterminated array");
                    }
                    sb.append(' ').append(stripComment(lines[ln]).strip());
                }
                rest = sb.toString();
            }
            int[] pos = {0};
            Object value = parseValue(rest, pos, ln + 1);
            if (!rest.substring(pos[0]).isBlank()) {
                throw new ParseException(ln + 1, "trailing characters after value");
            }
            current.put(key, value);
        }
        return new Table(root);
    }

    private static int depth(CharSequence s) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            }
        }
        return depth;
    }

    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int indexOfEquals(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '=') {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitPath(String header, int line) throws ParseException {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '.') {
                out.add(checkKey(cur.toString().strip(), line));
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(checkKey(cur.toString().strip(), line));
        return out;
    }

    private static String checkKey(String key, int line) throws ParseException {
        if (key.isEmpty()) {
            throw new ParseException(line, "empty key");
        }
        return key;
    }

    private static String parseKey(String raw, int line) throws ParseException {
        if (raw.length() >= 2 && (raw.charAt(0) == '"' || raw.charAt(0) == '\'') && raw.charAt(raw.length() - 1) == raw.charAt(0)) {
            return checkKey(raw.substring(1, raw.length() - 1), line);
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) {
                throw new ParseException(line, "invalid bare key '" + raw + "' (dotted keys unsupported, quote it)");
            }
        }
        return checkKey(raw, line);
    }

    private static Object parseValue(String s, int[] pos, int line) throws ParseException {
        skip(s, pos);
        if (pos[0] >= s.length()) {
            throw new ParseException(line, "missing value");
        }
        char c = s.charAt(pos[0]);
        if (c == '"') {
            return parseBasic(s, pos, line);
        }
        if (c == '\'') {
            int end = s.indexOf('\'', pos[0] + 1);
            if (end < 0) {
                throw new ParseException(line, "unterminated string");
            }
            String v = s.substring(pos[0] + 1, end);
            pos[0] = end + 1;
            return v;
        }
        if (c == '[') {
            pos[0]++;
            List<Object> list = new ArrayList<>();
            while (true) {
                skip(s, pos);
                if (pos[0] >= s.length()) {
                    throw new ParseException(line, "unterminated array");
                }
                if (s.charAt(pos[0]) == ']') {
                    pos[0]++;
                    return list;
                }
                list.add(parseValue(s, pos, line));
                skip(s, pos);
                if (pos[0] < s.length() && s.charAt(pos[0]) == ',') {
                    pos[0]++;
                }
            }
        }
        if (c == '{') {
            throw new ParseException(line, "inline tables unsupported");
        }
        int start = pos[0];
        while (pos[0] < s.length() && ",]".indexOf(s.charAt(pos[0])) < 0) {
            pos[0]++;
        }
        String token = s.substring(start, pos[0]).strip();
        switch (token) {
            case "true":
                return Boolean.TRUE;
            case "false":
                return Boolean.FALSE;
            default:
                break;
        }
        String num = token.replace("_", "");
        try {
            if (num.startsWith("0x") || num.startsWith("0X")) {
                return Long.parseLong(num.substring(2), 16);
            }
            if (num.matches("[+-]?\\d+")) {
                return Long.parseLong(num);
            }
            String lower = num.toLowerCase(Locale.ROOT);
            if (lower.matches("[+-]?(\\d+\\.\\d*|\\.\\d+|\\d+)(e[+-]?\\d+)?")) {
                return Double.parseDouble(num);
            }
        } catch (NumberFormatException ex) {
            throw new ParseException(line, "bad number '" + token + "'");
        }
        throw new ParseException(line, "unrecognised value '" + token + "'");
    }

    private static String parseBasic(String s, int[] pos, int line) throws ParseException {
        StringBuilder sb = new StringBuilder();
        int i = pos[0] + 1;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') {
                pos[0] = i;
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i >= s.length()) {
                break;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case 'u' -> {
                    if (i + 4 > s.length()) {
                        throw new ParseException(line, "bad \\u escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw new ParseException(line, "bad \\u escape");
                    }
                    i += 4;
                }
                default -> throw new ParseException(line, "bad escape \\" + e);
            }
        }
        throw new ParseException(line, "unterminated string");
    }

    private static void skip(String s, int[] pos) {
        while (pos[0] < s.length() && Character.isWhitespace(s.charAt(pos[0]))) {
            pos[0]++;
        }
    }
}
