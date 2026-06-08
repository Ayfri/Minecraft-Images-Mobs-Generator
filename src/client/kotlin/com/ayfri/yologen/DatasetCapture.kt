package com.ayfri.yologen

import com.mojang.blaze3d.platform.NativeImage
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import java.io.File
import java.util.concurrent.Executors

private const val TARGET_W = 1280
private const val TARGET_H = 720

data object DatasetCapture {
	private const val CAPTURE_EVERY_N_FRAMES = 20

	var autoMode = true

	@Volatile
	var pendingCapture = false

	private var frameCount = 0
	private var captureIndex = 0

	private val ioExecutor = Executors.newSingleThreadExecutor { r ->
		Thread(r, "yologen-io").also { it.isDaemon = true }
	}

	fun register() {

		LevelRenderEvents.END_MAIN.register { context ->
			val shouldCapture = pendingCapture || (autoMode && ++frameCount % CAPTURE_EVERY_N_FRAMES == 0)
			if (!shouldCapture) return@register
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

			Screenshot.takeScreenshot(mc.mainRenderTarget) { image ->
				ioExecutor.submit {
					image.use { image ->
						val imagesDir = File(gameDir, "dataset/images").also { it.mkdirs() }
						val labelsDir = File(gameDir, "dataset/labels").also { it.mkdirs() }

						val finalImage = if (image.width > TARGET_W || image.height > TARGET_H) {
							image.scaleDown()
						} else image

						try {
							finalImage.writeToFile(File(imagesDir, "$name.png"))
						} finally {
							if (finalImage !== image) finalImage.close()
						}

						File(labelsDir, "$name.txt").writeText(boxes.joinToString("\n") { it.toTxtLine() })
					}
				}
			}
		}
	}

	// Nearest-neighbor downscale using the bulk pixel getter (getPixelABGR is private).
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
