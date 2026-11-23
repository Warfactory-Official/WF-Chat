package com.wf.wfchat.core;

import com.wf.wfchat.api.ChannelSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** Live channel list for one connection: permanent channels from the {@link Registry} plus dynamic ones. */
public final class Channels {
    public static final class Channel {
        public final ChannelSpec spec;
        public final Component prefix;
        public int unread;

        Channel(ChannelSpec spec) {
            this.spec = spec;
            this.prefix = Component.literal("[").append(spec.title()).append("] ")
                    .withStyle(Style.EMPTY.withColor(spec.color()));
        }
    }

    private final Map<String, Channel> byId = new HashMap<>();
    private final List<Channel> ordered = new ArrayList<>();
    private int seenRegistryVersion = -1;
    private int version;

    public int version() {
        return version;
    }

    public int size() {
        return ordered.size();
    }

    public Channel at(int i) {
        return ordered.get(i);
    }

    public Channel get(String id) {
        return byId.get(id);
    }

    /** Re-syncs permanent channels when plugins registered more since the last call. */
    public void sync(Registry registry) {
        if (seenRegistryVersion == registry.version()) {
            return;
        }
        seenRegistryVersion = registry.version();
        for (ChannelSpec spec : registry.channelSpecs()) {
            put(spec);
        }
    }

    /** Adds or replaces. Replacing keeps the unread count. */
    public Channel put(ChannelSpec spec) {
        Channel old = byId.get(spec.id());
        Channel c = new Channel(spec);
        if (old != null) {
            c.unread = old.unread;
            ordered.set(ordered.indexOf(old), c);
        } else {
            ordered.add(c);
        }
        byId.put(spec.id(), c);
        ordered.sort((a, b) -> Integer.compare(a.spec.order(), b.spec.order()));
        version++;
        return c;
    }

    public Channel ensure(ChannelSpec spec) {
        Channel c = byId.get(spec.id());
        return c != null ? c : put(spec);
    }

    public boolean close(String id) {
        Channel c = byId.get(id);
        if (c == null || !c.spec.dynamic()) {
            return false;
        }
        byId.remove(id);
        ordered.remove(c);
        version++;
        return true;
    }

    public int priority(String id) {
        Channel c = byId.get(id);
        return c == null ? Integer.MAX_VALUE : c.spec.priority();
    }

    public void clearDynamic() {
        if (ordered.removeIf(c -> c.spec.dynamic())) {
            byId.values().removeIf(c -> c.spec.dynamic());
            version++;
        }
    }
}
