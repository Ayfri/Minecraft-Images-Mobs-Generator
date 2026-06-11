package com.ayfri.yologen

import com.ayfri.yologen.mixin.LevelRendererAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot

/**
 * Reads the entity-outline framebuffer (populated by Minecraft's glow-outline pass) and
 * computes a tight screen-space bounding box from the lit pixels.  This covers the full
 * rendered model - including parts that extend beyond the collision AABB (blaze rods,
 * ghast tentacles, warden appendages, etc.).
 *
 * The outline target contains the raw silhouette before the glow-ring post-process, so
 * cancelling [LevelRendererOutlineMixin.doEntityOutline] keeps the ring off the screenshot
 * while leaving the buffer intact for us to read here.
 *
 * Returns null if the buffer is unavailable or contains no lit pixels (mob fully occluded).
 *
 * Coordinate space: normalised [0,1] in YOLO format (cx, cy, w, h) relative to the full
 * window (before the crop applied by DatasetCapture).  The applyCrop() call in DatasetCapture
 * handles remapping to the cropped-image space, exactly like the legacy AABB boxes.
 */
fun readSilhouetteBox(mc: Minecraft, classId: Int, dist: Float): YoloBox? {
	val outlineTarget = (mc.levelRenderer as LevelRendererAccessor).entityOutlineTarget
		?: return null

	val screenW = mc.window.width
	val screenH = mc.window.height
	if (screenW <= 0 || screenH <= 0) return null

	var minX = Int.MAX_VALUE
	var minY = Int.MAX_VALUE
	var maxX = Int.MIN_VALUE
	var maxY = Int.MIN_VALUE
	var found = false

	// takeScreenshot gives us an ABGR NativeImage on the render thread.
	// We scan synchronously inside the callback (called immediately by the method).
	Screenshot.takeScreenshot(outlineTarget) { img ->
		img.use {
			val pixels = img.pixelsABGR
			val w = img.width
			val h = img.height
			for (y in 0 until h) {
				val row = y * w
				for (x in 0 until w) {
					val alpha = (pixels[row + x] ushr 24) and 0xFF
					if (alpha > 0) {
						if (x < minX) minX = x
						if (y < minY) minY = y
						if (x > maxX) maxX = x
						if (y > maxY) maxY = y
						found = true
					}
				}
			}
		}
	}

	if (!found) return null

	// Add 1-pixel padding to ensure the box fully covers the silhouette edge
	minX = (minX - 1).coerceAtLeast(0)
	minY = (minY - 1).coerceAtLeast(0)
	maxX = (maxX + 1).coerceAtMost(screenW - 1)
	maxY = (maxY + 1).coerceAtMost(screenH - 1)

	val bw = (maxX - minX).toFloat()
	val bh = (maxY - minY).toFloat()
	if (bw < 4f || bh < 4f) return null

	return YoloBox(
		classId = classId,
		x = (minX + bw / 2f) / screenW,
		y = (minY + bh / 2f) / screenH,
		w = bw / screenW,
		h = bh / screenH,
		dist = dist,
	)
}
