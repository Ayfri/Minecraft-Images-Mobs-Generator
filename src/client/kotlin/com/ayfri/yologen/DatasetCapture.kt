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

// 30% crop from each edge after scaling (1280×0.3=384, 720×0.3=216) → 512×288 final images
private const val CROP_X = 384
private const val CROP_Y = 216
private const val CROP_W = 512
private const val CROP_H = 288

private const val FRAMES_CSV_HEADER = "frame,mob,weather,time_ticks,shot,mob_idx,mob_x,mob_y,mob_z"
private const val BOXES_CSV_HEADER = "frame,class_id,cx,cy,w,h,dist_blocks"

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

	fun toCsvRow(frameName: String) =
		"$frameName,$mobName,$weather,$timeOfDay,$shotIndex,$mobIndex,${mobX.fmt()},${mobY.fmt()},${mobZ.fmt()}"
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
	private val csvLock = Any()

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
			val translucentTarget = mc.levelRenderer.translucentTarget

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

	private fun writeCapture(
		img: NativeImage,
		boxes: List<YoloBox>,
		metadata: CaptureMetadata?,
		name: String,
		gameDir: File,
	) {
		val scaledImage = if (img.width > TARGET_W || img.height > TARGET_H) img.scaleDown() else img
		val croppedImage = scaledImage.cropCenter()
		var croppedBoxes: List<YoloBox> = emptyList()
		try {
			croppedBoxes = boxes.mapNotNull { it.applyCrop() }
			if (croppedBoxes.isEmpty()) return
			val imagesDir = File(gameDir, "dataset/images").also { it.mkdirs() }
			croppedImage.writeToFile(File(imagesDir, "$name.png"))
		} finally {
			if (scaledImage !== img) scaledImage.close()
			croppedImage.close()
		}

		val datasetDir = File(gameDir, "dataset")
		synchronized(csvLock) {
			val framesFile = File(datasetDir, "frames.csv")
			val needsFramesHeader = !framesFile.exists()
			FileWriter(framesFile, true).use { w ->
				if (needsFramesHeader) w.appendLine(FRAMES_CSV_HEADER)
				if (metadata != null) w.appendLine(metadata.toCsvRow(name))
			}

			val boxesFile = File(datasetDir, "boxes.csv")
			val needsBoxesHeader = !boxesFile.exists()
			FileWriter(boxesFile, true).use { w ->
				if (needsBoxesHeader) w.appendLine(BOXES_CSV_HEADER)
				for (box in croppedBoxes) w.appendLine(box.toCsvRow(name))
			}
		}
	}

	// Porter-Duff "over": composites `trans` (the translucent layer) on top of `dst` in-place.
	// Both images must have the same dimensions. Pixels in ABGR format (alpha in bits 24-31).
	private fun compositeOver(dst: NativeImage, trans: NativeImage) {
		val w = dst.width
		val h = dst.height
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

	// Crops 30% from each edge (CROP_X/Y offset, CROP_W×CROP_H result).
	private fun NativeImage.cropCenter(): NativeImage {
		val dst = NativeImage(NativeImage.Format.RGBA, CROP_W, CROP_H, false)
		val src = pixelsABGR
		for (y in 0 until CROP_H) {
			val srcRow = (y + CROP_Y) * width + CROP_X
			for (x in 0 until CROP_W) {
				dst.setPixelABGR(x, y, src[srcRow + x])
			}
		}
		return dst
	}

	// Re-normalizes a YoloBox from TARGET space to the cropped image space. Returns null if the box
	// is entirely outside the crop region or too small after clipping.
	private fun YoloBox.applyCrop(): YoloBox? {
		val left = (x - w / 2f) * TARGET_W
		val right = (x + w / 2f) * TARGET_W
		val top = (y - h / 2f) * TARGET_H
		val bot = (y + h / 2f) * TARGET_H

		val cl = left.coerceAtLeast(CROP_X.toFloat())
		val cr = right.coerceAtMost((CROP_X + CROP_W).toFloat())
		val ct = top.coerceAtLeast(CROP_Y.toFloat())
		val cb = bot.coerceAtMost((CROP_Y + CROP_H).toFloat())

		val cw = cr - cl
		val ch = cb - ct
		if (cw < 5f || ch < 5f) return null

		return copy(
			x = (cl + cw / 2f - CROP_X) / CROP_W,
			y = (ct + ch / 2f - CROP_Y) / CROP_H,
			w = cw / CROP_W,
			h = ch / CROP_H,
		)
	}

	private fun YoloBox.toCsvRow(frameName: String) =
		"$frameName,$classId,$x,$y,$w,$h,$dist"
}
