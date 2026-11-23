package com.wf.wfchat.mixin;

import com.wf.wfchat.client.ChatHud;
import com.wf.wfchat.client.ClientChat;
//? if <26.1 {
import net.minecraft.client.GuiMessageTag;
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    //? if <1.21 {
    /*@Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void wfchat$render(GuiGraphics graphics, int tickCount, int mouseX, int mouseY, CallbackInfo ci) {
        ChatHud.render(graphics, tickCount, mouseX, mouseY, Minecraft.getInstance().screen instanceof ChatScreen);
        ci.cancel();
    }
    *///?} else if <26.1 {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void wfchat$render(GuiGraphics graphics, int tickCount, int mouseX, int mouseY, boolean focused,
                               CallbackInfo ci) {
        ChatHud.render(graphics, tickCount, mouseX, mouseY, focused);
        ci.cancel();
    }
    //?} else {
    /*@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void wfchat$render(GuiGraphics graphics, net.minecraft.client.gui.Font font, int tickCount, int mouseX,
                               int mouseY, ChatComponent.DisplayMode mode, boolean changeCursor, CallbackInfo ci) {
        ChatHud.render(graphics, tickCount, mouseX, mouseY, mode.foreground);
        ci.cancel();
    }
    *///?}

    //? if <26.1 {
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true)
    private void wfchat$add(Component message, CallbackInfo ci) {
        ClientChat.get().receive(message);
        ci.cancel();
    }

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"), cancellable = true)
    private void wfchat$addSigned(Component message, @Nullable MessageSignature signature, @Nullable GuiMessageTag tag,
                                  CallbackInfo ci) {
        ClientChat.get().receive(message);
        ci.cancel();
    }

    @Inject(method = "getClickedComponentStyleAt", at = @At("HEAD"), cancellable = true)
    private void wfchat$style(double mouseX, double mouseY, CallbackInfoReturnable<Style> cir) {
        cir.setReturnValue(ChatHud.styleAt(mouseX, mouseY));
    }

    @Inject(method = "getMessageTagAt", at = @At("HEAD"), cancellable = true)
    private void wfchat$tag(double mouseX, double mouseY, CallbackInfoReturnable<GuiMessageTag> cir) {
        cir.setReturnValue(null);
    }
    //?} else {
    /*@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("HEAD"), cancellable = true)
    private void wfchat$add(Component message, @Nullable MessageSignature signature,
                            net.minecraft.client.multiplayer.chat.GuiMessageSource source, @Nullable net.minecraft.client.multiplayer.chat.GuiMessageTag tag,
                            CallbackInfo ci) {
        ClientChat.get().receive(message);
        ci.cancel();
    }
    *///?}

    @Inject(method = "clearMessages", at = @At("HEAD"))
    private void wfchat$clear(boolean clearSentMsgHistory, CallbackInfo ci) {
        ClientChat.get().reset();
    }

    @Inject(method = "scrollChat", at = @At("HEAD"), cancellable = true)
    private void wfchat$scroll(int amount, CallbackInfo ci) {
        ClientChat.get().view.scrollBy(amount, ChatHud.linesPerPage(true));
        ci.cancel();
    }

    @Inject(method = "resetChatScroll", at = @At("HEAD"), cancellable = true)
    private void wfchat$resetScroll(CallbackInfo ci) {
        ClientChat.get().view.resetScroll();
        ci.cancel();
    }

    @Inject(method = "rescaleChat", at = @At("HEAD"), cancellable = true)
    private void wfchat$rescale(CallbackInfo ci) {
        ClientChat.get().view.invalidate();
        ci.cancel();
    }

    @Inject(method = "getLinesPerPage", at = @At("HEAD"), cancellable = true)
    private void wfchat$lines(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(ChatHud.linesPerPage(Minecraft.getInstance().screen instanceof ChatScreen));
    }
}
