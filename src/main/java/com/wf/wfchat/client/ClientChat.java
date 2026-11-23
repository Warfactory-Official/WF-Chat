package com.wf.wfchat.client;

import com.wf.wfchat.api.ChannelCommand;
import com.wf.wfchat.api.ChannelSpec;
import com.wf.wfchat.api.IncomingMessage;
import com.wf.wfchat.api.ServerInfo;
import com.wf.wfchat.core.Builtin;
import com.wf.wfchat.emoji.EmojiText;
import com.wf.wfchat.emoji.Emojis;
import com.wf.wfchat.core.ChatLog;
import com.wf.wfchat.core.ChatTypes;
import com.wf.wfchat.core.Channels;
import com.wf.wfchat.core.Compat;
import com.wf.wfchat.core.MsgChannelCommand;
import com.wf.wfchat.core.Registry;
import com.wf.wfchat.core.Router;
import com.wf.wfchat.core.Selection;
import com.wf.wfchat.core.Stamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** All client chat state: channels, the single message log, the listened selection. */
public final class ClientChat {
    private static final ClientChat INSTANCE = new ClientChat();

    public final Channels channels = new Channels();
    public final Selection selection = new Selection(Builtin.GLOBAL);
    public final ChatLog<Message> log = new ChatLog<>(4096);
    public final ChatView view = new ChatView(this);
    public final ServerInfo server = () -> serverHasMod();
    private final Vanity vanity = new Vanity();
    private final java.util.Set<Long> revealed = new java.util.HashSet<>();
    private ChatConfig config = new ChatConfig();
    private final Router router = new Router(Registry.get(), channels, Nearby::test);
    private boolean initialised;
    private @Nullable PlayerContext playerContext;

    private ClientChat() {
    }

    public static ClientChat get() {
        return INSTANCE;
    }

    private void init() {
        if (initialised) {
            return;
        }
        initialised = true;
        Builtin.registerDefaults(Registry.get());
        Registry.get().channelCommand(new MsgChannelCommand());
        config = ChatConfig.load();
        vanity.init();
        Emojis.get().start();
        channels.sync(Registry.get());
    }

    public void sync() {
        init();
        com.wf.wfchat.emoji.ServerEmojiSync.tick(serverHasMod());
        channels.sync(Registry.get());
        for (int i = selection.size() - 1; i >= 0; i--) {
            if (channels.get(selection.get(i)) == null) {
                selection.remove(selection.get(i), Builtin.GLOBAL);
            }
        }
    }

    public void setPlayerContext(@Nullable PlayerContext context) {
        this.playerContext = context;
    }

    public boolean serverHasMod() {
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        return conn != null && conn.getCommands().getRoot().getChild("wfchat") != null;
    }

    public boolean spoilerRevealed(long id) {
        return revealed.contains(id);
    }

    public void toggleSpoiler(long id) {
        if (!revealed.remove(id)) {
            revealed.add(id);
        }
    }

    public void reset() {
        log.clear();
        revealed.clear();
        channels.clearDynamic();
        selection.only(Builtin.GLOBAL);
        for (int i = 0; i < channels.size(); i++) {
            channels.at(i).unread = 0;
        }
        view.invalidate();
    }

    public void receive(Component raw) {
        if (com.wf.wfchat.emoji.ServerEmojiSync.consume(raw)) {
            return;
        }
        Stamp stamp = Stamp.read(raw);
        Component content = stamp != null ? stamp.inner() : raw;
        PlayerContext ctx = playerContext;
        if (ctx == null && stamp != null && stamp.sender() != null) {
            ctx = new PlayerContext(stamp.sender(), stamp.senderName(), null);
        }
        String type = ChatTypes.of(content);
        IncomingMessage.Kind kind = ctx != null ? IncomingMessage.Kind.PLAYER
                : type != null ? IncomingMessage.Kind.DISGUISED : IncomingMessage.Kind.SYSTEM;
        String senderName = ctx != null ? ctx.name() : null;
        String targetName = null;
        if (type != null) {
            String first = resolvePlayer(ChatTypes.firstArg(content));
            if (type.endsWith("_outgoing")) {
                targetName = first;
            } else if (senderName == null) {
                senderName = first;
            }
        }
        IncomingMessage msg = new IncomingMessage(kind, content, type, ctx != null ? ctx.id() : null, senderName,
                targetName, ctx != null ? ctx.body() : null, stamp != null ? stamp.channel() : null);
        sync();
        String[] chans = router.route(msg);
        int tick = Minecraft.getInstance().gui.getGuiTicks();
        Component styled = type != null ? StyleText.apply(content, config) : content;
        log.append(chans, tick, new Message(EmojiText.transform(vanity.apply(styled, ctx)), tick));
        for (String id : chans) {
            Channels.Channel c = channels.get(id);
            if (c != null && !selection.contains(id)) {
                c.unread++;
            }
        }
    }

    /** The player typed {@code /name args...}: opens the channel of any matching {@link ChannelCommand}. */
    public void onTypedCommand(String line) {
        String[] parts = line.substring(1).trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return;
        }
        String name = parts[0].toLowerCase(Locale.ROOT);
        List<String> args = new ArrayList<>(parts.length);
        for (int i = 1; i < parts.length; i++) {
            args.add(parts[i]);
        }
        for (ChannelCommand command : Registry.get().commands()) {
            if (!command.names().contains(name)) {
                continue;
            }
            Optional<ChannelSpec> opened = command.onTyped(name, args);
            if (opened.isPresent()) {
                channels.ensure(opened.get());
                selection.only(opened.get().id());
                channels.get(opened.get().id()).unread = 0;
                return;
            }
        }
    }

    /** Line to send for text typed with the current selection; commands pass through. */
    public String outgoing(String raw) {
        String typed = EmojiText.encode(raw);
        if (typed.startsWith("/")) {
            onTypedCommand(typed);
            return typed;
        }
        Channels.Channel target = channels.get(selection.target());
        return target == null ? typed : target.spec.sender().wrap(typed, server);
    }

    public void select(String id, boolean additive) {
        if (additive) {
            selection.toggle(id);
        } else {
            selection.only(id);
        }
        for (int i = 0; i < selection.size(); i++) {
            Channels.Channel c = channels.get(selection.get(i));
            if (c != null) {
                c.unread = 0;
            }
        }
    }

    public void toggle(String id) {
        select(id, true);
    }

    public void close(String id) {
        if (channels.close(id)) {
            selection.remove(id, Builtin.GLOBAL);
        }
    }

    @Nullable
    private static String resolvePlayer(@Nullable String text) {
        if (text == null) {
            return null;
        }
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        if (conn == null) {
            return text;
        }
        String best = null;
        for (PlayerInfo info : conn.getOnlinePlayers()) {
            String n = Compat.name(info.getProfile());
            if (text.contains(n) && (best == null || n.length() > best.length())) {
                best = n;
            }
        }
        return best != null ? best : text;
    }
}
