package com.wf.wfchat.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.wf.wfchat.api.WfChatServer;
import com.wf.wfchat.core.Compat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** {@code /wfchat local|channel}: server-side routing for channels that restrict recipients. */
public final class ServerChannels {
    private static final Map<String, WfChatServer.Recipients> CHANNELS = new ConcurrentHashMap<>();

    private ServerChannels() {
    }

    public static void register(String id, WfChatServer.Recipients recipients) {
        CHANNELS.put(id, recipients);
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wfchat")
                .then(ServerEmoji.command())
                .then(Commands.literal("local")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> deliver(ctx, "local", ServerChannels::nearby))))
                .then(Commands.literal("channel")
                        .then(Commands.argument("id", StringArgumentType.string())
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(ServerChannels::custom)))));
    }

    private static int custom(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        WfChatServer.Recipients r = CHANNELS.get(id);
        if (r == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown channel " + id));
            return 0;
        }
        return deliver(ctx, id, r);
    }

    private static int deliver(CommandContext<CommandSourceStack> ctx, String channel, WfChatServer.Recipients r) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sender)) {
            ctx.getSource().sendFailure(Component.literal("Players only"));
            return 0;
        }
        String text = StringArgumentType.getString(ctx, "message");
        Component line = Component.translatable("chat.type.text", sender.getDisplayName(), text);
        Collection<ServerPlayer> to = r.of(sender);
        WfChatServer.send(channel, to, sender, line);
        if (!to.contains(sender)) {
            sender.sendSystemMessage(WfChatServer.stamp(channel, sender, line));
        }
        return 1;
    }

    private static Collection<ServerPlayer> nearby(ServerPlayer sender) {
        int range = sender.level().getServer().getPlayerList().getViewDistance();
        int cx = Compat.cx(sender.chunkPosition());
        int cz = Compat.cz(sender.chunkPosition());
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : sender.level().getServer().getPlayerList().getPlayers()) {
            if (p.level() == sender.level()
                    && Math.abs(Compat.cx(p.chunkPosition()) - cx) <= range
                    && Math.abs(Compat.cz(p.chunkPosition()) - cz) <= range) {
                out.add(p);
            }
        }
        return out;
    }
}
