package com.ayfri.yologen

import com.ayfri.yologen.config.ConfigHolder
import com.mojang.blaze3d.platform.NativeImage
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileWriter
import java.util.*
import java.util.concurrent.Executors
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.sqrt

private const val FRAMES_CSV_HEADER = "frame,mob,weather,time_ticks,shot,mob_idx,mob_x,mob_y,mob_z,negative"
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
	val negative: Boolean = false,
) {
	private fun Double.fmt() = String.format(Locale.ROOT, "%.2f", this)

	fun toCsvRow(frameName: String) =
		"$frameName,$mobName,$weather,$timeOfDay,$shotIndex,$mobIndex,${mobX.fmt()},${mobY.fmt()},${mobZ.fmt()},${if (negative) 1 else 0}"
}

data object DatasetCapture {
	private const val CAPTURE_EVERY_N_FRAMES = 20

	var autoMode = true
	var debugBBMode = false
	var debugClassId: Int? = null

	@Volatile
	var pendingCapture = false

	/** Set by the render thread after consuming a fired capture: true if an image was actually
	 *  written, false if it was dropped (no usable box). Read by AutoCapture to advance/retry. */
	@Volatile
	var lastCaptureSucceeded = false

	@Volatile
	var pendingCaptureMetadata: CaptureMetadata? = null

	private var frameCount = 0
	internal var captureIndex = 0

	internal fun resumeFrom(index: Int) {
		captureIndex = index
	}

	private val ioExecutor = Executors.newFixedThreadPool(4) { r ->
		Thread(r, "yologen-io").also { it.isDaemon = true }
	}
	private val csvLock = Any()

	fun register() {
		LevelRenderEvents.END_MAIN.register { context ->
			val awaited = pendingCapture
			val shouldCapture = pendingCapture || (autoMode && ++frameCount % CAPTURE_EVERY_N_FRAMES == 0)
			if (!shouldCapture) return@register
			val metadata = pendingCaptureMetadata.also { pendingCaptureMetadata = null }

			val mc = Minecraft.getInstance()
			val cfg = ConfigHolder.config

			val screenW = mc.window.width
			val screenH = mc.window.height

			// Reports the outcome of an awaited capture back to AutoCapture (success = image written).
			// Ordering matters: set the result BEFORE clearing pendingCapture so the tick thread,
			// which only proceeds once pendingCapture is false, always reads a settled result.
			fun finish(success: Boolean): Boolean {
				if (awaited) {
					lastCaptureSucceeded = success
					pendingCapture = false
				}
				return success
			}

			val isNegative = metadata?.negative == true

			// Negative frames: the camera faces away from the mob, so the frame is background-only
			//   by construction → no boxes, written unconditionally.
			// Primary path (!autoMode = auto-capture running):
			//   - Known class ID → pixel-perfect silhouette from entity-outline buffer.
			//     The mob is tagged glowing by AutoCapture.spawnMobEntity; the glow ring
			//     is suppressed by LevelRendererOutlineMixin so it never reaches the screenshot.
			//   - No class ID (multi-mob diversity) → AABB projector per entity.
			//
			// Fallback path (autoMode = manual/explore mode): AABB projector.
			val boxes: List<YoloBox>
			if (isNegative) {
				boxes = emptyList()
			} else if (!autoMode && AutoCapture.running) {
				val classId = YOLO_CLASS_MAP[AutoCapture.currentMobEntityType]
				if (classId != null && !cfg.multipleMobsPerFrame) {
					val player = mc.player ?: return@register
					val dx = AutoCapture.mobX - player.x
					val dy = (AutoCapture.mobY + 1.0) - player.eyeY
					val dz = AutoCapture.mobZ - player.z
					val dist = sqrt(dx * dx + dy * dy + dz * dz).toFloat()
					val sBox = readSilhouetteBox(mc, classId, dist)
					if (sBox != null) {
						boxes = listOf(sBox)
					} else {
						// Silhouette buffer unavailable - fall back to AABB projection
						val levelState = context.levelState()
						val camera = levelState.cameraRenderState
						boxes = levelState.entityRenderStates.mapNotNull { it.toYoloBox(camera, screenW, screenH) }
						if (boxes.isEmpty()) { finish(false); return@register }
					}
				} else {
					// Multi-mob per frame or multi-mob diversity: AABB per entity for individual boxes
					val levelState = context.levelState()
					val camera = levelState.cameraRenderState
					boxes = levelState.entityRenderStates.mapNotNull { it.toYoloBox(camera, screenW, screenH) }
					if (boxes.isEmpty()) { finish(false); return@register }
				}
			} else if (debugBBMode) {
				val classId = debugClassId
				if (classId != null && metadata != null) {
					val player = mc.player ?: return@register
					val dx = metadata.mobX - player.x
					val dy = (metadata.mobY + 1.0) - player.eyeY
					val dz = metadata.mobZ - player.z
					val dist = sqrt(dx * dx + dy * dy + dz * dz).toFloat()
					val sBox = readSilhouetteBox(mc, classId, dist)
					if (sBox != null) {
						boxes = listOf(sBox)
					} else {
						val levelState = context.levelState()
						val camera = levelState.cameraRenderState
						boxes = levelState.entityRenderStates.mapNotNull { it.toYoloBox(camera, screenW, screenH) }
						if (boxes.isEmpty()) { finish(false); return@register }
					}
				} else {
					val levelState = context.levelState()
					val camera = levelState.cameraRenderState
					boxes = levelState.entityRenderStates.mapNotNull { it.toYoloBox(camera, screenW, screenH) }
					if (boxes.isEmpty()) { finish(false); return@register }
				}
			} else {
				// Auto-mode or manual capture: AABB for all visible entities
				val levelState = context.levelState()
				val camera = levelState.cameraRenderState
				boxes = levelState.entityRenderStates.mapNotNull { it.toYoloBox(camera, screenW, screenH) }
				if (boxes.isEmpty()) { finish(false); return@register }
			}

			// Synchronous crop-survival check for the awaited auto-capture path: the shot only
			// counts (and advances) if at least one box survives the centre crop. This keeps the
			// tick's shot counter in lock-step with images actually written to disk.
			if (awaited && !isNegative && !debugBBMode) {
				val survives = boxes.any {
					it.applyCrop(cfg.targetWidth, cfg.targetHeight, cfg.cropX, cfg.cropY, cfg.cropWidth, cfg.cropHeight) != null
				}
				if (!survives) { finish(false); return@register }
			}
			finish(true)

			val idx = captureIndex++
			val name = "frame_%06d".format(idx)
			val gameDir = mc.gameDirectory

			val targetW = cfg.targetWidth
			val targetH = cfg.targetHeight
			val cropX = cfg.cropX
			val cropY = cfg.cropY
			val cropW = cfg.cropWidth
			val cropH = cfg.cropHeight
			val imageFormat = cfg.imageFormat
			val jpegQuality = cfg.jpegQuality

			// In Fabulous graphics mode, translucent terrain is rendered to a separate
			// framebuffer that hasn't been composited yet. Composite on the IO thread.
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
								writeCapture(
									main, boxes, metadata, name, gameDir,
									targetW, targetH, cropX, cropY, cropW, cropH,
									imageFormat, jpegQuality
								)
							}
						}
					}
				} else {
					ioExecutor.submit {
						mainImg.use { img ->
							writeCapture(
								img, boxes, metadata, name, gameDir,
								targetW, targetH, cropX, cropY, cropW, cropH,
								imageFormat, jpegQuality
							)
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
		targetW: Int,
		targetH: Int,
		cropX: Int,
		cropY: Int,
		cropW: Int,
		cropH: Int,
		imageFormat: String = "jpg",
		jpegQuality: Float = 0.90f,
	) {
		val isNegative = metadata?.negative == true
		val scaledImage = if (img.width > targetW || img.height > targetH)
			img.scaleDown(targetW, targetH) else img
		val croppedImage = scaledImage.cropCenter(cropX, cropY, cropW, cropH)
		var croppedBoxes: List<YoloBox>
		try {
			croppedBoxes = boxes.mapNotNull { it.applyCrop(targetW, targetH, cropX, cropY, cropW, cropH) }
			// Allow empty boxes for negative frames; skip only non-negative frames with no visible mob.
			if (croppedBoxes.isEmpty() && !isNegative) return

			if (debugBBMode) {
				croppedImage.drawBoundingBoxes(croppedBoxes)
				val debugDir = File(gameDir, "dataset/debug").also { it.mkdirs() }
				val mobName = metadata?.mobName ?: name
				croppedImage.writeToFile(File(debugDir, "$mobName.png"))
				return
			}

			val ext = if (imageFormat.lowercase() == "jpg" || imageFormat.lowercase() == "jpeg") "jpg" else "png"
			val imagesDir = File(gameDir, "dataset/images").also { it.mkdirs() }
			val outFile = File(imagesDir, "$name.$ext")
			if (ext == "jpg") {
				croppedImage.writeJpeg(outFile, jpegQuality)
			} else {
				croppedImage.writeToFile(outFile)
			}
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

			if (!isNegative) {
				val boxesFile = File(datasetDir, "boxes.csv")
				val needsBoxesHeader = !boxesFile.exists()
				FileWriter(boxesFile, true).use { w ->
					if (needsBoxesHeader) w.appendLine(BOXES_CSV_HEADER)
					for (box in croppedBoxes) w.appendLine(box.toCsvRow(name))
				}
			}
		}
	}

	private fun NativeImage.writeJpeg(file: File, quality: Float) {
		val w = width; val h = height
		val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
		val pixels = pixelsABGR
		// NativeImage ABGR int: bits 0-7=R, 8-15=G, 16-23=B, 24-31=A
		for (y in 0 until h) {
			val row = y * w
			for (x in 0 until w) {
				val abgr = pixels[row + x]
				val r = abgr and 0xFF
				val g = (abgr ushr 8) and 0xFF
				val b = (abgr ushr 16) and 0xFF
				bi.setRGB(x, y, (r shl 16) or (g shl 8) or b)
			}
		}
		val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
		val param = writer.defaultWriteParam.also {
			it.compressionMode = ImageWriteParam.MODE_EXPLICIT
			it.compressionQuality = quality
		}
		ImageIO.createImageOutputStream(file).use { out ->
			writer.output = out
			writer.write(null, IIOImage(bi, null, null), param)
			writer.dispose()
		}
	}

	// Porter-Duff "over": composites `trans` on top of `dst` in-place.
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
				if (alpha == 255) {
					dst.setPixelABGR(x, y, s); continue
				}
				val d = dstPx[row + x]
				val t = alpha / 255f
				val r = ((s and 0xFF) * t + (d and 0xFF) * (1f - t)).toInt()
				val g = (((s ushr 8) and 0xFF) * t + ((d ushr 8) and 0xFF) * (1f - t)).toInt()
				val b = (((s ushr 16) and 0xFF) * t + ((d ushr 16) and 0xFF) * (1f - t)).toInt()
				dst.setPixelABGR(x, y, (0xFF shl 24) or (b shl 16) or (g shl 8) or r)
			}
		}
	}

	// Nearest-neighbor downscale.
	private fun NativeImage.scaleDown(targetW: Int, targetH: Int): NativeImage {
		val dst = NativeImage(NativeImage.Format.RGBA, targetW, targetH, false)
		val src = pixelsABGR
		val scaleX = width.toFloat() / targetW
		val scaleY = height.toFloat() / targetH
		for (y in 0 until targetH) {
			val srcRow = (y * scaleY).toInt() * width
			for (x in 0 until targetW) {
				dst.setPixelABGR(x, y, src[srcRow + (x * scaleX).toInt()])
			}
		}
		return dst
	}

	// Crops from (cropX, cropY) for cropW×cropH pixels.
	private fun NativeImage.cropCenter(cropX: Int, cropY: Int, cropW: Int, cropH: Int): NativeImage {
		val dst = NativeImage(NativeImage.Format.RGBA, cropW, cropH, false)
		val src = pixelsABGR
		for (y in 0 until cropH) {
			val srcRow = (y + cropY) * width + cropX
			for (x in 0 until cropW) {
				dst.setPixelABGR(x, y, src[srcRow + x])
			}
		}
		return dst
	}

	// Re-normalises a YoloBox from TARGET space to the cropped-image space.
	private fun YoloBox.applyCrop(
		targetW: Int, targetH: Int,
		cropX: Int, cropY: Int, cropW: Int, cropH: Int,
	): YoloBox? {
		val left = (x - w / 2f) * targetW
		val right = (x + w / 2f) * targetW
		val top = (y - h / 2f) * targetH
		val bot = (y + h / 2f) * targetH

		val cl = left.coerceAtLeast(cropX.toFloat())
		val cr = right.coerceAtMost((cropX + cropW).toFloat())
		val ct = top.coerceAtLeast(cropY.toFloat())
		val cb = bot.coerceAtMost((cropY + cropH).toFloat())

		val cw = cr - cl
		val ch = cb - ct
		if (cw < 5f || ch < 5f) return null

		return copy(
			x = (cl + cw / 2f - cropX) / cropW,
			y = (ct + ch / 2f - cropY) / cropH,
			w = cw / cropW,
			h = ch / cropH,
		)
	}

	private fun YoloBox.toCsvRow(frameName: String) =
		"$frameName,$classId,$x,$y,$w,$h,$dist"

	// ABGR colors cycling per class ID - same palette as Python cv2 debug visualisations
	private val BB_COLORS = intArrayOf(
		0xFF00FF00.toInt(), // green
		0xFF0000FF.toInt(), // red
		0xFFFF0000.toInt(), // blue
		0xFF00FFFF.toInt(), // yellow
		0xFFFF00FF.toInt(), // magenta
		0xFFFFFF00.toInt(), // cyan
		0xFF0080FF.toInt(), // orange
		0xFF80FF00.toInt(), // lime
	)

	private fun NativeImage.drawBoundingBoxes(boxes: List<YoloBox>) {
		val w = width
		val h = height
		for (box in boxes) {
			val color = BB_COLORS[box.classId % BB_COLORS.size]
			val x1 = ((box.x - box.w / 2f) * w).toInt().coerceIn(0, w - 1)
			val y1 = ((box.y - box.h / 2f) * h).toInt().coerceIn(0, h - 1)
			val x2 = ((box.x + box.w / 2f) * w).toInt().coerceIn(0, w - 1)
			val y2 = ((box.y + box.h / 2f) * h).toInt().coerceIn(0, h - 1)
			// 2-pixel border thickness
			for (t in 0..1) {
				for (px in x1..x2) {
					if (y1 + t < h) setPixelABGR(px, y1 + t, color)
					if (y2 - t >= 0) setPixelABGR(px, y2 - t, color)
				}
				for (py in y1..y2) {
					if (x1 + t < w) setPixelABGR(x1 + t, py, color)
					if (x2 - t >= 0) setPixelABGR(x2 - t, py, color)
				}
			}
		}
	}
}
