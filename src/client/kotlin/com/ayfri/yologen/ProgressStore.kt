package com.ayfri.yologen

import net.minecraft.client.Minecraft
import java.io.File
import java.util.concurrent.Executors

internal data object ProgressStore {
	private val ioExecutor = Executors.newSingleThreadExecutor { r ->
		Thread(r, "yologen-progress").also { it.isDaemon = true }
	}

	private fun file(mc: Minecraft) = File(mc.gameDirectory, "dataset/progress.txt")

	fun load(mc: Minecraft): Set<String> {
		val f = file(mc)
		if (!f.exists()) return emptySet()
		return f.readLines().filter { it.isNotBlank() }.toSet()
	}

	fun markCompleted(mc: Minecraft, mobName: String) {
		ioExecutor.submit {
			val f = file(mc)
			f.parentFile?.mkdirs()
			f.appendText("$mobName\n")
		}
	}

	// Scans dataset/images for the highest existing frame index so writes resume without gaps.
	fun resumeCaptureIndex(mc: Minecraft): Int {
		val dir = File(mc.gameDirectory, "dataset/images")
		if (!dir.exists()) return 0
		return dir.listFiles { f ->
			f.name.startsWith("frame_") && (f.name.endsWith(".png") || f.name.endsWith(".jpg"))
		}?.mapNotNull { it.nameWithoutExtension.removePrefix("frame_").toIntOrNull() }
			?.maxOrNull()?.plus(1) ?: 0
	}
}
