package com.ayfri.yologen

import com.mojang.blaze3d.platform.NativeImage
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import java.io.File
import java.io.FileWriter
import java.util.*
import java.util.concurrent.Executors

private const val TARGET_W = 1280
private const val TARGET_H = 720

data class CaptureMetadata(
	val mobName: String,
	val mobX: Double,
	val mobY: Double,
	val mobZ: Double,
	val weather: String,
	val timeOfDay: Long,
	val shotIndex: Int,
	val mobIndex: Int,
) {
	private fun Double.fmt() = String.format(Locale.ROOT, "%.2f", this)

	fun toJsonLine(frameName: String) =
		"""{"frame":"$frameName","mob":"$mobName","x":${mobX.fmt()},"y":${mobY.fmt()},"z":${mobZ.fmt()},"weather":"$weather","time_ticks":$timeOfDay,"shot":$shotIndex,"mob_idx":$mobIndex}"""
}

data object DatasetCapture {
	private const val CAPTURE_EVERY_N_FRAMES = 20

	var autoMode = true

	@Volatile
	var pendingCapture = false

	@Volatile
	var pendingCaptureMetadata: CaptureMetadata? = null

	private var frameCount = 0
	private var captureIndex = 0

	private val ioExecutor = Executors.newFixedThreadPool(4) { r ->
		Thread(r, "yologen-io").also { it.isDaemon = true }
	}
	private val metadataLock = Any()

	fun register() {
		LevelRenderEvents.END_MAIN.register { context ->
			val shouldCapture = pendingCapture || (autoMode && ++frameCount % CAPTURE_EVERY_N_FRAMES == 0)
			if (!shouldCapture) return@register
			val metadata = pendingCaptureMetadata.also { pendingCaptureMetadata = null }
			pendingCapture = false

			val mc = Minecraft.getInstance()
			val levelState = context.levelState()
			val camera = levelState.cameraRenderState
			val screenW = mc.window.width
			val screenH = mc.window.height

			val boxes = levelState.entityRenderStates.mapNotNull {
				it.toYoloBox(camera, screenW, screenH)
			}
			if (boxes.isEmpty()) return@register

			val idx = captureIndex++
			val name = "frame_%06d".format(idx)
			val gameDir = mc.gameDirectory

			// In Fabulous graphics mode, translucent terrain (water) is rendered to a separate
			// framebuffer that hasn't been composited onto mainRenderTarget at END_MAIN yet.
			// We read both targets here on the render thread, then composite on the IO thread.
			val translucentTarget = mc.levelRenderer.getTranslucentTarget()

			Screenshot.takeScreenshot(mc.mainRenderTarget) { mainImg ->
				if (translucentTarget != null) {
					Screenshot.takeScreenshot(translucentTarget) { transImg ->
						ioExecutor.submit {
							mainImg.use { main ->
								transImg.use { trans ->
									if (trans.width == main.width && trans.height == main.height) {
										compositeOver(main, trans)
									}
								}
								writeCapture(main, boxes, metadata, name, gameDir)
							}
						}
					}
				} else {
					ioExecutor.submit {
						mainImg.use { img ->
							writeCapture(img, boxes, metadata, name, gameDir)
						}
					}
				}
			}
		}
	}

	private fun writeCapture(img: NativeImage, boxes: List<YoloBox>, metadata: CaptureMetadata?, name: String, gameDir: File) {
		val imagesDir = File(gameDir, "dataset/images").also { it.mkdirs() }
		val labelsDir = File(gameDir, "dataset/labels").also { it.mkdirs() }

		val finalImage = if (img.width > TARGET_W || img.height > TARGET_H) img.scaleDown() else img
		try {
			finalImage.writeToFile(File(imagesDir, "$name.png"))
		} finally {
			if (finalImage !== img) finalImage.close()
		}

		File(labelsDir, "$name.txt").writeText(boxes.joinToString("\n") { it.toTxtLine() })

		if (metadata != null) {
			synchronized(metadataLock) {
				FileWriter(File(gameDir, "dataset/metadata.jsonl"), true).use { w ->
					w.appendLine(metadata.toJsonLine(name))
				}
			}
		}
	}

	// Porter-Duff "over": composites `trans` (the translucent layer) on top of `dst` in-place.
	// Both images must have the same dimensions. Pixels in ABGR format (alpha in bits 24-31).
	private fun compositeOver(dst: NativeImage, trans: NativeImage) {
		val w = dst.width; val h = dst.height
		val dstPx = dst.pixelsABGR
		val srcPx = trans.pixelsABGR
		for (y in 0 until h) {
			val row = y * w
			for (x in 0 until w) {
				val s = srcPx[row + x]
				val alpha = (s ushr 24) and 0xFF
				if (alpha == 0) continue
				if (alpha == 255) { dst.setPixelABGR(x, y, s); continue }
				val d = dstPx[row + x]
				val t = alpha / 255f
				val r = ((s and 0xFF) * t + (d and 0xFF) * (1f - t)).toInt()
				val g = (((s ushr 8) and 0xFF) * t + ((d ushr 8) and 0xFF) * (1f - t)).toInt()
				val b = (((s ushr 16) and 0xFF) * t + ((d ushr 16) and 0xFF) * (1f - t)).toInt()
				dst.setPixelABGR(x, y, (0xFF shl 24) or (b shl 16) or (g shl 8) or r)
			}
		}
	}

	// Nearest-neighbor downscale using the bulk pixel getter.
	private fun NativeImage.scaleDown(): NativeImage {
		val dst = NativeImage(NativeImage.Format.RGBA, TARGET_W, TARGET_H, false)
		val src = pixelsABGR
		val scaleX = width.toFloat() / TARGET_W
		val scaleY = height.toFloat() / TARGET_H
		for (y in 0 until TARGET_H) {
			val srcRow = (y * scaleY).toInt() * width
			for (x in 0 until TARGET_W) {
				dst.setPixelABGR(x, y, src[srcRow + (x * scaleX).toInt()])
			}
		}
		return dst
	}
}
