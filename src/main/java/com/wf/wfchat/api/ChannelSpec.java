package com.wf.wfchat.api;

import java.util.Objects;
import net.minecraft.network.chat.Component;

/**
 * Immutable description of a channel. Lower {@link #order()} = button listed earlier; lower
 * {@link #priority()} = more specific, wins the origin prefix when a message is in several channels.
 */
public final class ChannelSpec {
    private final String id;
    private final Component title;
    private final int color;
    private final int order;
    private final int priority;
    private final boolean dynamic;
    private final Sender sender;

    private ChannelSpec(Builder b) {
        this.id = b.id;
        this.title = b.title != null ? b.title : Component.literal(b.id);
        this.color = b.color;
        this.order = b.order;
        this.priority = b.priority;
        this.dynamic = b.dynamic;
        this.sender = b.sender;
    }

    public String id() {
        return id;
    }

    public Component title() {
        return title;
    }

    /** RGB, no alpha. */
    public int color() {
        return color;
    }

    public int order() {
        return order;
    }

    public int priority() {
        return priority;
    }

    /** Dynamic channels are created at runtime (by a {@link ChannelCommand}) and can be closed by the player. */
    public boolean dynamic() {
        return dynamic;
    }

    public Sender sender() {
        return sender;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private Component title;
        private int color = 0xFFFFFF;
        private int order = 100;
        private int priority = 100;
        private boolean dynamic;
        private Sender sender = Sender.CHAT;

        private Builder(String id) {
            this.id = Objects.requireNonNull(id);
        }

        public Builder title(Component title) {
            this.title = title;
            return this;
        }

        public Builder title(String literal) {
            this.title = Component.literal(literal);
            return this;
        }

        public Builder color(int rgb) {
            this.color = rgb & 0xFFFFFF;
            return this;
        }

        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder dynamic(boolean dynamic) {
            this.dynamic = dynamic;
            return this;
        }

        public Builder sender(Sender sender) {
            this.sender = Objects.requireNonNull(sender);
            return this;
        }

        public ChannelSpec build() {
            return new ChannelSpec(this);
        }
    }
}
