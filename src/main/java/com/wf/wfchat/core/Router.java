package com.wf.wfchat.core;

import com.wf.wfchat.api.ChannelCommand;
import com.wf.wfchat.api.ChannelSink;
import com.wf.wfchat.api.ChannelSpec;
import com.wf.wfchat.api.Classifier;
import com.wf.wfchat.api.IncomingMessage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** Decides which channels an {@link IncomingMessage} belongs to. */
public final class Router {
    private final Registry registry;
    private final Channels channels;
    private final Predicate<IncomingMessage> nearby;

    public Router(Registry registry, Channels channels, Predicate<IncomingMessage> nearby) {
        this.registry = registry;
        this.channels = channels;
        this.nearby = nearby;
    }

    /** Channel ids, most specific first (lowest priority value). Never empty. */
    public String[] route(IncomingMessage message) {
        Sink sink = new Sink(message);
        if (message.stampedChannel() != null) {
            sink.add(message.stampedChannel());
            sink.claim();
        }
        for (ChannelCommand command : registry.commands()) {
            if (sink.claimed()) {
                break;
            }
            Optional<ChannelSpec> opened = command.onIncoming(message);
            if (opened.isPresent()) {
                sink.add(opened.get());
                sink.claim();
            }
        }
        for (Classifier classifier : registry.classifiers()) {
            classifier.classify(message, sink);
        }
        if (!sink.claimed()) {
            boolean player = message.kind() == IncomingMessage.Kind.PLAYER
                    || message.kind() == IncomingMessage.Kind.DISGUISED;
            if (player) {
                sink.add(Builtin.GLOBAL);
                if (nearby.test(message)) {
                    sink.add(Builtin.LOCAL);
                }
            } else {
                sink.add(Builtin.SYSTEM);
            }
        }
        if (sink.ids.isEmpty()) {
            sink.ids.add(Builtin.SYSTEM);
        }
        String[] out = sink.ids.toArray(new String[0]);
        Arrays.sort(out, (a, b) -> Integer.compare(channels.priority(a), channels.priority(b)));
        return out;
    }

    private final class Sink implements ChannelSink {
        final List<String> ids = new ArrayList<>(2);
        private final IncomingMessage message;
        private boolean claimed;

        Sink(IncomingMessage message) {
            this.message = message;
        }

        @Override
        public void add(String channelId) {
            if (channels.get(channelId) != null && !ids.contains(channelId)) {
                ids.add(channelId);
            }
        }

        @Override
        public void add(ChannelSpec spec) {
            channels.ensure(spec);
            add(spec.id());
        }

        @Override
        public void claim() {
            claimed = true;
        }

        @Override
        public boolean claimed() {
            return claimed;
        }

        @Override
        public boolean senderIsNearby(IncomingMessage m) {
            return nearby.test(m);
        }
    }
}
