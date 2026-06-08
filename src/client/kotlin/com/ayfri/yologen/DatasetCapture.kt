package com.ayfri.yologen

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import java.io.File
import java.util.concurrent.Executors

object DatasetCapture {
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
                    try {
                        val imagesDir = File(gameDir, "dataset/images").also { it.mkdirs() }
                        val labelsDir = File(gameDir, "dataset/labels").also { it.mkdirs() }
                        image.writeToFile(File(imagesDir, "$name.png"))
                        File(labelsDir, "$name.txt").writeText(boxes.joinToString("\n") { it.toTxtLine() })
                    } finally {
                        image.close()
                    }
                }
            }
        }
    }
}
