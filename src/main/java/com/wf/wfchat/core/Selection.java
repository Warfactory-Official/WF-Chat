package com.wf.wfchat.core;

import java.util.ArrayList;
import java.util.List;

/** Channels the player is listening to, plus the one typed text goes to. */
public final class Selection {
    private final List<String> ids = new ArrayList<>(4);
    private String target;
    private int version;

    public Selection(String initial) {
        ids.add(initial);
        target = initial;
    }

    public int version() {
        return version;
    }

    public int size() {
        return ids.size();
    }

    public String get(int i) {
        return ids.get(i);
    }

    public boolean contains(String id) {
        return ids.contains(id);
    }

    /** Typed text goes here. Always a member of the selection. */
    public String target() {
        return target;
    }

    /** Plain click: exactly this channel. */
    public void only(String id) {
        if (ids.size() == 1 && ids.get(0).equals(id)) {
            return;
        }
        ids.clear();
        ids.add(id);
        target = id;
        version++;
    }

    /** Shift-click: add to or remove from the listened set; the last channel cannot be removed. */
    public void toggle(String id) {
        if (ids.contains(id)) {
            if (ids.size() == 1) {
                return;
            }
            ids.remove(id);
            if (target.equals(id)) {
                target = ids.get(ids.size() - 1);
            }
        } else {
            ids.add(id);
            target = id;
        }
        version++;
    }

    /** Drops a closed channel; falls back to {@code fallback} when nothing is left. */
    public void remove(String id, String fallback) {
        if (!ids.remove(id)) {
            return;
        }
        if (ids.isEmpty()) {
            ids.add(fallback);
        }
        if (target.equals(id)) {
            target = ids.get(ids.size() - 1);
        }
        version++;
    }

    /** First of {@code entryChannels} (most specific first) that is selected, or null. */
    public String origin(String[] entryChannels) {
        for (String c : entryChannels) {
            if (ids.contains(c)) {
                return c;
            }
        }
        return null;
    }

    /** True when the entry belongs to at least one selected channel. */
    public boolean accepts(String[] entryChannels) {
        return origin(entryChannels) != null;
    }
}
