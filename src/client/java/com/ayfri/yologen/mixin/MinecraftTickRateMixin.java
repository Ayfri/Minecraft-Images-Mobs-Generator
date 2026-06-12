package com.ayfri.yologen.mixin;

import com.ayfri.yologen.AutoCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.TickRateManager;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MinecraftTickRateMixin {

    @Shadow
    @Nullable
    public ClientLevel level;

    /**
     * Removes the Math.max clamp in getTickTargetMillis so the client tick rate
     * can match the server's elevated tick rate while auto-capture is running.
     * Without this, the client always ticks at ≥ 50ms (20 TPS) regardless of
     * what the server is doing, preventing turbo-speed captures.
     */
    @Inject(method = "getTickTargetMillis", at = @At("HEAD"), cancellable = true)
    private void yologen_unclampTickRate(float defaultTickTargetMillis, CallbackInfoReturnable<Float> cir) {
        if (AutoCapture.isRunning() && this.level != null) {
            TickRateManager manager = this.level.tickRateManager();
            if (manager.runsNormally()) {
                cir.setReturnValue(manager.millisecondsPerTick());
            }
        }
    }
}
