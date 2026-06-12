package com.ayfri.yologen

import net.minecraft.client.Minecraft
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.clock.WorldClocks
import net.minecraft.world.level.gamerules.GameRules
import java.util.Locale

internal fun Double.fmt(decimals: Int = 2) = String.format(Locale.ROOT, "%.${decimals}f", this)
internal fun Int.toChunkCoord() = floorDiv(16)

internal fun serverPlayer(mc: Minecraft): ServerPlayer? =
	mc.singleplayerServer?.playerList?.players?.firstOrNull()

internal fun applyInstantTime(mc: Minecraft, time: Long) {
	val server = mc.singleplayerServer
	if (server != null) {
		server.execute {
			val clockManager = server.clockManager()
			val clockHolder = server.registryAccess()
				.lookupOrThrow(Registries.WORLD_CLOCK)
				.getOrThrow(WorldClocks.OVERWORLD)
			val current = clockManager.getTotalTicks(clockHolder)
			val aligned = (current / 24000L) * 24000L + time
			clockManager.setTotalTicks(clockHolder, aligned)
		}
		return
	}
	mc.player?.connection?.sendCommand("time set $time")
}

internal fun applyInstantWeather(mc: Minecraft, weather: String) {
	val server = mc.singleplayerServer
	if (server != null) {
		server.execute {
			when (weather) {
				"rain" -> server.setWeatherParameters(0, 24000, true, false)
				"thunder" -> server.setWeatherParameters(0, 24000, true, true)
				else -> server.setWeatherParameters(24000, 0, false, false)
			}
		}
		return
	}
	mc.player?.connection?.sendCommand("weather $weather")
}

internal fun setGameRulesDirect(
	mc: Minecraft,
	spawnMobs: Boolean,
	advTime: Boolean,
	advWeather: Boolean,
	randomTick: Int,
) {
	val server = mc.singleplayerServer
	if (server != null) {
		server.execute {
			server.gameRules.set(GameRules.SPAWN_MOBS, spawnMobs, server)
			server.gameRules.set(GameRules.SPAWN_MONSTERS, spawnMobs, server)
			server.gameRules.set(GameRules.ADVANCE_TIME, advTime, server)
			server.gameRules.set(GameRules.ADVANCE_WEATHER, advWeather, server)
			server.gameRules.set(GameRules.RANDOM_TICK_SPEED, randomTick, server)
		}
	} else {
		val cmds = mc.player?.connection ?: return
		cmds.sendCommand("gamerule doMobSpawning ${if (spawnMobs) "true" else "false"}")
		cmds.sendCommand("gamerule doDaylightCycle ${if (advTime) "true" else "false"}")
		cmds.sendCommand("gamerule doWeatherCycle ${if (advWeather) "true" else "false"}")
		cmds.sendCommand("gamerule randomTickSpeed $randomTick")
	}
}
