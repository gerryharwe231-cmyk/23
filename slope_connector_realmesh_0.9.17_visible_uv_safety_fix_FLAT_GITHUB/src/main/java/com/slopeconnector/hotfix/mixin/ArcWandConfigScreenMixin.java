package com.slopeconnector.hotfix.mixin;

import com.slopeconnector.hotfix.client.ArcAutoTrimClientState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.slopeconnector.client.ArcWandConfigScreen", remap = false)
public abstract class ArcWandConfigScreenMixin extends Screen {
    protected ArcWandConfigScreenMixin() { super(Text.empty()); }

    @Inject(method = "method_25426", at = @At("TAIL"), remap = false)
    private void addAutoTrimButton(CallbackInfo ci) {
        // Original controls stop at y=158. Put passive trim on a new left-side row, completely
        // separated from the right-side save/clear/close controls.
        ButtonWidget button = ButtonWidget.builder(Text.literal(ArcAutoTrimClientState.label()), b -> {
                    ArcAutoTrimClientState.toggle();
                    b.setMessage(Text.literal(ArcAutoTrimClientState.label()));
                })
                .dimensions(18, 166, 130, 20)
                .build();
        this.addDrawableChild(button);
    }

    @Inject(method = "method_25394", at = @At("HEAD"), remap = false)
    private void extendLeftPanel(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        context.fill(8, 164, 158, 194, 0xB0181818);
    }
}
