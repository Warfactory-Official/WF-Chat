package com.wf.wfchat.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** One chat-bridge matcher from {@code config/wfchat-bridges.toml}: which lines a bridge mod produces, and where they go. */
public record BridgeRule(String id, String title, int color, boolean pin, Pattern pattern) {
    public static final String DEFAULT_PATTERN = "^\\[Discord\\b[^\\]]*\\]";

    public boolean matches(String plainText) {
        return pattern.matcher(plainText).find();
    }

    /** Rules from the {@code [bridge.<id>]} tables; invalid ones are reported to {@code errors} and skipped. */
    public static List<BridgeRule> parse(Toml.Table root, List<String> errors) {
        List<BridgeRule> out = new ArrayList<>();
        Toml.Table bridges = root.table("bridge");
        for (String id : bridges.keys()) {
            Toml.Table t = bridges.table(id);
            String raw = t.string("pattern", "");
            if (raw.isEmpty()) {
                errors.add("bridge." + id + ": missing pattern");
                continue;
            }
            Pattern pattern;
            try {
                pattern = Pattern.compile(raw);
            } catch (PatternSyntaxException ex) {
                errors.add("bridge." + id + ": bad pattern: " + ex.getDescription());
                continue;
            }
            String mode = t.string("mode", "pin");
            if (!mode.equals("pin") && !mode.equals("own")) {
                errors.add("bridge." + id + ": mode must be \"pin\" or \"own\"");
                continue;
            }
            int color;
            try {
                color = Integer.parseInt(t.string("color", "5865F2").replaceFirst("^#", ""), 16);
            } catch (NumberFormatException ex) {
                errors.add("bridge." + id + ": color must be hex RRGGBB");
                continue;
            }
            out.add(new BridgeRule("bridge:" + id, t.string("title", id), color & 0xFFFFFF, mode.equals("pin"), pattern));
        }
        return out;
    }
}
