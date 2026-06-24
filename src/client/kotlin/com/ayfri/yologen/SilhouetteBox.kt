package com.ayfri.yologen

import com.ayfri.yologen.DatasetCapture.applyCrop
import com.ayfri.yologen.mixin.LevelRendererAccessor
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft

/**
 * The entity-outline framebuffer is populated by Minecraft's glow-outline pass (every mob is
 * tagged glowing by AutoCapture). Cancelling [com.ayfri.yologen.mixin.LevelRendererOutlineMixin.doEntityOutline]
 * keeps the glow ring off the screenshot while leaving the raw silhouette in this buffer.
 *
 * IMPORTANT: reading this buffer must happen INSIDE a `Screenshot.takeScreenshot` callback.
 * In 26.1 `Screenshot.takeScreenshot` issues the GPU→buffer copy immediately but runs the
 * consumer behind a GPU fence (`RenderSystem.queueFencedTask`), one frame later. Reading the
 * pixels synchronously right after the call returns nothing - which is why the old
 * `readSilhouetteBox` always returned null and every box silently fell back to the AABB.
 */
fun outlineTarget(mc: Minecraft): RenderTarget? =
	(mc.levelRenderer as LevelRendererAccessor).entityOutlineTarget

fun hasOutlineBuffer(mc: Minecraft): Boolean = outlineTarget(mc) != null

/**
 * Computes a tight screen-space bounding box from the lit pixels of an already-grabbed outline
 * image. Covers the full rendered model - parts that extend beyond the collision AABB (dragon
 * wings, ghast tentacles, warden appendages, etc.) included.
 *
 * Returns null if the image contains no lit pixels (mob fully occluded) or the box is < 4px.
 * Coordinates are normalised [0,1] relative to the image; [DatasetCapture.applyCrop] remaps
 * them into the cropped-image space exactly like the AABB boxes.
 */
fun computeSilhouetteBox(img: NativeImage, classId: Int, dist: Float): YoloBox? {
	val w = img.width
	val h = img.height
	if (w <= 0 || h <= 0) return null

	var minX = Int.MAX_VALUE
	var minY = Int.MAX_VALUE
	var maxX = Int.MIN_VALUE
	var maxY = Int.MIN_VALUE
	var found = false

	val pixels = img.pixelsABGR
	for (y in 0..<h) {
		val row = y * w
		for (x in 0..<w) {
			// Screenshot.takeScreenshot forces alpha=0xFF, so alpha is useless as a discriminator.
			// The outline buffer clears to black; entity pixels carry a non-black team colour.
			if ((pixels[row + x] and 0x00FFFFFF) != 0) {
				if (x < minX) minX = x
				if (y < minY) minY = y
				if (x > maxX) maxX = x
				if (y > maxY) maxY = y
				found = true
			}
		}
	}

	if (!found) return null

	// 1-pixel padding to fully cover the silhouette edge.
	minX = (minX - 1).coerceAtLeast(0)
	minY = (minY - 1).coerceAtLeast(0)
	maxX = (maxX + 1).coerceAtMost(w - 1)
	maxY = (maxY + 1).coerceAtMost(h - 1)

	val bw = (maxX - minX).toFloat()
	val bh = (maxY - minY).toFloat()
	if (bw < 4f || bh < 4f) return null

	return YoloBox(
		classId = classId,
		x = (minX + bw / 2f) / w,
		y = (minY + bh / 2f) / h,
		w = bw / w,
		h = bh / h,
		dist = dist,
	)
}
