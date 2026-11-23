package com.wf.wfchat.mixin;

import com.wf.wfchat.client.ChatHud;
import com.wf.wfchat.client.ClientChat;
import com.wf.wfchat.client.ChatScreenAccess;
import com.wf.wfchat.client.EmojiSuggest;
import com.wf.wfchat.client.ToolButtons;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin implements ChatScreenAccess {
    @Shadow
    protected EditBox input;

    @Override
    public EditBox wfchat$input() {
        return input;
    }

    //? if <26.1 {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void wfchat$click(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (ToolButtons.click(mouseX, mouseY, Screen.hasShiftDown(), input) || EmojiSuggest.click(mouseX, mouseY, input) || ChatHud.clickSpoiler(mouseX, mouseY) || ChatHud.buttons().click(mouseX, mouseY, button, Screen.hasShiftDown())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void wfchat$key(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (ToolButtons.key(keyCode, input) || EmojiSuggest.key(keyCode, input)) {
            cir.setReturnValue(true);
        }
    }
    //?} else {
    /*@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void wfchat$click(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick,
                              CallbackInfoReturnable<Boolean> cir) {
        if (ToolButtons.click(event.x(), event.y(), event.hasShiftDown(), input) || EmojiSuggest.click(event.x(), event.y(), input) || ChatHud.clickSpoiler(event.x(), event.y())
                || ChatHud.buttons().click(event.x(), event.y(), event.button(), event.hasShiftDown())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void wfchat$key(net.minecraft.client.input.KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (ToolButtons.key(event.key(), input) || EmojiSuggest.key(event.key(), input)) {
            cir.setReturnValue(true);
        }
    }
    *///?}

    @ModifyVariable(method = "handleChatInput", at = @At("HEAD"), argsOnly = true)
    private String wfchat$route(String message) {
        return message.isBlank() ? message : ClientChat.get().outgoing(message);
    }
}
