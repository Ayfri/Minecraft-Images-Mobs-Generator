package com.ayfri.yologen

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.io.File
import java.util.concurrent.Executors

private val clearExecutor = Executors.newSingleThreadExecutor { r ->
	Thread(r, "yologen-clear").also { it.isDaemon = true }
}

fun registerCommands() {
	ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
		dispatcher.register(
			ClientCommands.literal("yologen").executes { ctx ->
				val mc = ctx.source.client
				if (AutoCapture.running) AutoCapture.stop(mc) else AutoCapture.start(mc)
				1
			}
		)
		dispatcher.register(
			ClientCommands.literal("yolostop").executes { ctx ->
				val mc = ctx.source.client
				if (AutoCapture.running) AutoCapture.stop(mc)
				else mc.player?.sendSystemMessage(Component.literal("§c[YoloGen] Not running."))
				1
			}
		)
		dispatcher.register(
			ClientCommands.literal("yoloclear").executes { ctx ->
				clearDataset(ctx.source.client)
				1
			}
		)
	}
}

private fun clearDataset(mc: Minecraft) {
	if (AutoCapture.running) AutoCapture.stop(mc)
	val dir = File(mc.gameDirectory, "dataset")
	if (!dir.exists()) {
		mc.player?.sendSystemMessage(Component.literal("§e[YoloGen] No dataset folder found."))
		return
	}
	clearExecutor.submit {
		val count = dir.walkTopDown().count { it.isFile }
		dir.deleteRecursively()
		mc.execute {
			AutoCapture.onClear()
			mc.player?.sendSystemMessage(Component.literal("§c[YoloGen] Deleted $count files from dataset/ - progress reset."))
		}
	}
	mc.player?.sendSystemMessage(Component.literal("§e[YoloGen] Clearing dataset in background…"))
}
