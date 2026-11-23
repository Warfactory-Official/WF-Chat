package com.wf.wfchat.core;

import com.wf.wfchat.api.ChannelCommand;
import com.wf.wfchat.api.ChannelSpec;
import com.wf.wfchat.api.ChatRegistry;
import com.wf.wfchat.api.Classifier;
import com.wf.wfchat.api.VanityProvider;
import com.wf.wfchat.api.WfChatPlugin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process-wide plugin registry. Channel list order: {@link ChannelSpec#order()}, then registration. */
public final class Registry implements ChatRegistry {
    private static final Registry INSTANCE = new Registry();

    private final Map<String, WfChatPlugin> plugins = new LinkedHashMap<>();
    private final Map<String, ChannelSpec> channels = new LinkedHashMap<>();
    private final List<Classifier> classifiers = new CopyOnWriteArrayList<>();
    private final List<ChannelCommand> commands = new CopyOnWriteArrayList<>();
    private final List<VanityProvider> vanity = new CopyOnWriteArrayList<>();
    private volatile int version;

    private Registry() {
    }

    public static Registry get() {
        return INSTANCE;
    }

    public synchronized void register(WfChatPlugin plugin) {
        if (plugins.putIfAbsent(plugin.id(), plugin) != null) {
            throw new IllegalStateException("duplicate chat plugin " + plugin.id());
        }
        plugin.register(this);
    }

    @Override
    public synchronized void channel(ChannelSpec spec) {
        channels.put(spec.id(), spec);
        version++;
    }

    @Override
    public void classifier(Classifier classifier) {
        classifiers.add(classifier);
    }

    @Override
    public void channelCommand(ChannelCommand command) {
        commands.add(command);
    }

    @Override
    public void vanity(VanityProvider provider) {
        vanity.add(provider);
    }

    @Override
    public void glyph(String name, String font, char glyph) {
        GlyphTable.put(name, font, glyph);
    }

    public synchronized List<ChannelSpec> channelSpecs() {
        return new ArrayList<>(channels.values());
    }

    public List<Classifier> classifiers() {
        return classifiers;
    }

    public List<ChannelCommand> commands() {
        return commands;
    }

    public List<VanityProvider> vanityProviders() {
        return vanity;
    }

    /** Bumped when the permanent channel list changes. */
    public int version() {
        return version;
    }
}
