package com.ayfri.yologen.mixin;

import com.ayfri.yologen.AutoCapture;
import com.ayfri.yologen.DebugBBCapture;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public class LevelRendererOutlineMixin {

    /**
     * Forces the entity-outline framebuffer to be populated while auto-capture is running,
     * so we can read the silhouette even when no glowing entities exist normally.
     */
    @Inject(method = "shouldShowEntityOutlines", at = @At("HEAD"), cancellable = true)
    private void yologen_forceEntityOutlines(CallbackInfoReturnable<Boolean> cir) {
        if (AutoCapture.isRunning() || DebugBBCapture.isRunning()) {
            cir.setReturnValue(true);
        }
    }

    /**
     * Suppresses compositing the glow ring onto the screen while capturing,
     * so the ring never appears in saved screenshots.
     * The outline buffer is still populated by the earlier main render pass.
     */
    @Inject(method = "doEntityOutline", at = @At("HEAD"), cancellable = true)
    private void yologen_suppressOutlineComposite(CallbackInfo ci) {
        if (AutoCapture.isRunning() || DebugBBCapture.isRunning()) {
            ci.cancel();
        }
    }
}
