package com.ayfri.yologen

import net.minecraft.client.Minecraft
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Relative
import net.minecraft.world.clock.WorldClocks
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.gamerules.GameRules

internal fun AutoCapture.serverPlayer(mc: Minecraft): ServerPlayer? =
	mc.singleplayerServer?.playerList?.players?.firstOrNull()

internal fun AutoCapture.forceServerChunksAround(mc: Minecraft, x: Int, z: Int, radius: Int = PRELOAD_CHUNK_RADIUS) {
	val server = mc.singleplayerServer ?: return
	val cx = x.toChunkCoord()
	val cz = z.toChunkCoord()
	server.execute {
		val sLevel = server.getLevel(currentDimensionKey) ?: return@execute
		for (dx in -radius..radius)
			for (dz in -radius..radius)
				sLevel.chunkSource.getChunk(cx + dx, cz + dz, ChunkStatus.FULL, true)
	}
}

internal fun AutoCapture.applyInstantTime(mc: Minecraft, time: Long) {
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

internal fun AutoCapture.applyInstantWeather(mc: Minecraft, weather: String) {
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

/** Moves the CLIENT player directly (no round-trip) and syncs the server async. */
internal fun AutoCapture.teleportPlayerDirect(mc: Minecraft, x: Double, y: Double, z: Double) {
	mc.player?.snapTo(x, y, z)
	val server = mc.singleplayerServer
	if (server != null) {
		server.execute { serverPlayer(mc)?.teleportTo(x, y, z) }
	} else {
		mc.player?.connection?.sendCommand("tp @s ${x.fmt()} ${y.fmt()} ${z.fmt()}")
	}
}

/** Cross-dimension teleport for phase transitions. */
internal fun AutoCapture.teleportPlayerToDimension(mc: Minecraft, x: Double, y: Double, z: Double) {
	val server = mc.singleplayerServer
	if (server != null) {
		server.execute {
			val sp = serverPlayer(mc) ?: return@execute
			val targetLevel = server.getLevel(currentDimensionKey) ?: return@execute
			if (sp.level().dimension() == currentDimensionKey) {
				sp.teleportTo(x, y, z)
			} else {
				sp.teleportTo(targetLevel, x, y, z, emptySet<Relative>(), sp.yRot, sp.xRot, false)
			}
		}
	} else {
		mc.player?.connection?.sendCommand("tp @s ${x.fmt()} ${y.fmt()} ${z.fmt()}")
	}
}

internal fun AutoCapture.preloadPosition(mc: Minecraft, x: Double, z: Double) {
	val preloadY = if (currentDimension == MobDimension.NETHER) NETHER_PRELOAD_HEIGHT else PRELOAD_HEIGHT
	forceServerChunksAround(mc, x.toInt(), z.toInt())
	teleportPlayerToDimension(mc, x, preloadY, z)
}

/**
 * Forces server-side chunk generation for the next mob's base position without
 * teleporting the player - so terrain is ready by the time setup actually starts.
 * Only kicks off generation; the actual teleport happens at setupTick==0.
 */
internal fun AutoCapture.preloadNextSetupPosition(mc: Minecraft, targetDimKey: ResourceKey<Level>) {
    val server = mc.singleplayerServer ?: return
    if (targetDimKey != currentDimensionKey) return  // cross-dimension: skip, player teleport handles it
    val nx = nextSetupBaseX ?: return
    val nz = nextSetupBaseZ ?: return
    val cx = nx.toChunkCoord()
    val cz = nz.toChunkCoord()
    server.execute {
        val sLevel = server.getLevel(targetDimKey) ?: return@execute
        for (dx in -PRELOAD_CHUNK_RADIUS..PRELOAD_CHUNK_RADIUS)
            for (dz in -PRELOAD_CHUNK_RADIUS..PRELOAD_CHUNK_RADIUS)
                sLevel.chunkSource.getChunk(cx + dx, cz + dz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true)
    }
}

internal fun AutoCapture.clearMobFire(mc: Minecraft) {
	val server = mc.singleplayerServer ?: return
	server.execute {
		val sLevel = server.getLevel(currentDimensionKey) ?: return@execute
		sLevel.allEntities.filter { it.entityTags().contains(MOB_TAG) }.forEach { it.clearFire() }
	}
}

internal fun AutoCapture.setGameRulesDirect(mc: Minecraft, spawnMobs: Boolean, advTime: Boolean, advWeather: Boolean, randomTick: Int) {
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
