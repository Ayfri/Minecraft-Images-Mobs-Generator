package com.ayfri.yologen.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    /** Exposes the private entity-outline framebuffer for silhouette reads. */
    @Accessor("entityOutlineTarget")
    @Nullable RenderTarget getEntityOutlineTarget();
}
