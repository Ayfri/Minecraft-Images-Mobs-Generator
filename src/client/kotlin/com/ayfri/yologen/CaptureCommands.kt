package com.ayfri.yologen

import com.ayfri.yologen.config.ConfigHolder
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
		// /yologen - toggle auto-capture on/off
		dispatcher.register(
			ClientCommands.literal("yologen").executes { ctx ->
				val mc = ctx.source.client
				if (AutoCapture.running) AutoCapture.stop(mc) else AutoCapture.start(mc)
				1
			}
		)

		// /yolostop - stop any running capture
		dispatcher.register(
			ClientCommands.literal("yolostop").executes { ctx ->
				val mc = ctx.source.client
				when {
					AutoCapture.running -> AutoCapture.stop(mc)
					DebugBBCapture.running -> DebugBBCapture.stop(mc)
					else -> mc.player?.sendSystemMessage(Component.literal("§c[YoloGen] Not running."))
				}
				1
			}
		)

		// /yoloclear - delete dataset + reset progress
		dispatcher.register(
			ClientCommands.literal("yoloclear").executes { ctx ->
				clearDataset(ctx.source.client)
				1
			}
		)

		// /yolodebugbb - standalone debug: 1 frontal shot per mob in the air → dataset/debug/
		dispatcher.register(
			ClientCommands.literal("yolodebugbb").executes { ctx ->
				val mc = ctx.source.client
				if (AutoCapture.running || DebugBBCapture.running) {
					mc.player?.sendSystemMessage(Component.literal("§c[YoloGen] Already running - /yolostop first."))
				} else {
					DebugBBCapture.start(mc)
				}
				1
			}
		)

		// /yoloreload - reload config from disk at runtime
		dispatcher.register(
			ClientCommands.literal("yoloreload").executes { ctx ->
				reloadConfig(ctx.source.client)
				1
			}
		)
	}
}

private fun reloadConfig(mc: Minecraft) {
	val cfg = ConfigHolder.reload(mc)
	mc.player?.sendSystemMessage(
		Component.literal(
			"§a[YoloGen] §fConfig reloaded. §8shots/mob=§f${cfg.shotsPerMob} §8renderDist=§f${cfg.captureRenderDistance} " +
				"§8babies=§f${cfg.babies} §8variants=§f${cfg.variants} §8equip=§f${cfg.equipmentAndPoses} " +
				"§8multiMob=§f${cfg.multipleMobsPerFrame} §8jitter=§f${cfg.cameraJitterAndLighting}"
		)
	)
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
