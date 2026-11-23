package com.wf.wfchat.mixin;

import com.wf.wfchat.client.ToolButtons;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EditBox.class)
public abstract class EditBoxMixin {
    //? if <26.1 {
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void wfchat$typed(char c, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (ToolButtons.typed((EditBox) (Object) this, c)) {
            cir.setReturnValue(true);
        }
    }
    //?} else {
    /*@Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void wfchat$typed(net.minecraft.client.input.CharacterEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (ToolButtons.typed((EditBox) (Object) this, event.codepoint())) {
            cir.setReturnValue(true);
        }
    }
    *///?}
}
