package com.wf.wfchat.mixin;

import com.mojang.authlib.GameProfile;
import com.wf.wfchat.client.ClientChat;
import com.wf.wfchat.core.Compat;
import com.wf.wfchat.client.PlayerContext;
import java.time.Instant;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatListener.class)
public abstract class ChatListenerMixin {
    @Inject(method = "showMessageToPlayer", at = @At("HEAD"))
    private void wfchat$begin(ChatType.Bound bound, PlayerChatMessage message, Component decorated,
                              GameProfile profile, boolean onlySecure, Instant timestamp,
                              CallbackInfoReturnable<Boolean> cir) {
        ClientChat.get().setPlayerContext(new PlayerContext(Compat.id(profile), Compat.name(profile), message.signedContent()));
    }

    @Inject(method = "showMessageToPlayer", at = @At("RETURN"))
    private void wfchat$end(ChatType.Bound bound, PlayerChatMessage message, Component decorated,
                            GameProfile profile, boolean onlySecure, Instant timestamp,
                            CallbackInfoReturnable<Boolean> cir) {
        ClientChat.get().setPlayerContext(null);
    }
}
