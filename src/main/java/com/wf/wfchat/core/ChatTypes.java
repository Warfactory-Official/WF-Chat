package com.wf.wfchat.core;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jetbrains.annotations.Nullable;

/** Recovers the vanilla chat type of a decorated line from its translation key. */
public final class ChatTypes {
    private ChatTypes() {
    }

    @Nullable
    public static TranslatableContents translatable(Component c) {
        return c.getContents() instanceof TranslatableContents t ? t : null;
    }

    /** Registry-key style name, or null when {@code c} is not a vanilla chat decoration. */
    @Nullable
    public static String of(Component c) {
        TranslatableContents t = translatable(c);
        if (t == null) {
            return null;
        }
        return switch (t.getKey()) {
            case "chat.type.text" -> "minecraft:chat";
            case "chat.type.emote" -> "minecraft:emote_command";
            case "chat.type.announcement" -> "minecraft:say_command";
            case "chat.type.team.text" -> "minecraft:team_msg_command_incoming";
            case "chat.type.team.sent" -> "minecraft:team_msg_command_outgoing";
            case "commands.message.display.incoming" -> "minecraft:msg_command_incoming";
            case "commands.message.display.outgoing" -> "minecraft:msg_command_outgoing";
            default -> null;
        };
    }

    /** Plain text of the decoration's first argument (sender or target name), or null. */
    @Nullable
    public static String firstArg(Component c) {
        TranslatableContents t = translatable(c);
        if (t == null || t.getArgs().length == 0) {
            return null;
        }
        Object a = t.getArgs()[0];
        return a instanceof Component ac ? ac.getString() : String.valueOf(a);
    }
}
