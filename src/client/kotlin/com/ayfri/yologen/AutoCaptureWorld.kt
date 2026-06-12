package com.ayfri.yologen

import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.Relative
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.server.level.TicketType

internal fun AutoCapture.forceServerChunksAround(mc: Minecraft, x: Int, z: Int, radius: Int = PRELOAD_CHUNK_RADIUS) {
	val server = mc.singleplayerServer ?: return
	val pos = ChunkPos(x.toChunkCoord(), z.toChunkCoord())
	server.execute {
		val sLevel = server.getLevel(currentDimensionKey) ?: return@execute
		// Non-blocking: queue async generation on server worker threads.
		sLevel.chunkSource.addTicketAndLoadWithRadius(TicketType.FORCED, pos, radius)
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
	val pos = ChunkPos(nx.toChunkCoord(), nz.toChunkCoord())
	server.execute {
		val sLevel = server.getLevel(targetDimKey) ?: return@execute
		sLevel.chunkSource.addTicketAndLoadWithRadius(TicketType.FORCED, pos, PRELOAD_CHUNK_RADIUS)
	}
}

internal fun AutoCapture.clearMobFire(mc: Minecraft) {
	val server = mc.singleplayerServer ?: return
	server.execute {
		val sLevel = server.getLevel(currentDimensionKey) ?: return@execute
		sLevel.allEntities.filter { it.entityTags().contains(MOB_TAG) }.forEach { it.clearFire() }
	}
}
