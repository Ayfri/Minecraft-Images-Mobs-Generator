package com.ayfri.yologen

import com.ayfri.yologen.config.ConfigHolder
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

internal fun AutoCapture.tickSetup(mc: Minecraft) {
	val player = mc.player ?: return
	if (setupTick == 0) {
		val entry = MOB_ENTRIES[mobIndex]
		currentDimension = entry.dimensions[dimensionPhaseIndex]
		currentDimensionKey = currentDimension.levelKey

		val server = mc.singleplayerServer
		if (server != null) {
			val dimKey = currentDimensionKey
			server.execute {
				val sLevel = server.getLevel(dimKey) ?: return@execute
				for (level in server.allLevels) {
					level.allEntities.filter { it !is ServerPlayer }.forEach { it.discard() }
				}
				serverPlayer(mc)?.gameMode?.changeGameModeForPlayer(GameType.SPECTATOR)
			}
		} else {
			player.connection.sendCommand("kill @e[type=!player]")
			player.connection.sendCommand("gamemode spectator")
		}

		baseTime = Random.nextLong(0, 24000)
		val range = basePosRange
		baseX = nextSetupBaseX?.also { nextSetupBaseX = null } ?: Random.nextInt(range.first, range.last + 1)
		baseZ = nextSetupBaseZ?.also { nextSetupBaseZ = null } ?: Random.nextInt(range.first, range.last + 1)

		if (dimensionPhaseIndex == 0) resetMobState() else resetDimPhaseState()

		buildPoolForCurrentDimension(mc)
		preloadPosition(mc, baseX.toDouble(), baseZ.toDouble())

		if (dimensionPhaseIndex > 0) {
			mc.player?.sendSystemMessage(
				Component.literal("§e[YoloGen] §f$currentMobName §8→ §7${currentDimension.label} §8(phase ${dimensionPhaseIndex + 1}/${entry.dimensions.size})")
			)
		}
	}

	// Build the biome relocation pool incrementally (a few biomes per tick) to avoid a long freeze.
	if (!poolBuildDone) tickBuildPool(mc)

	if (setupTick >= MOB_SPAWN_TICK && !mobSpawned) {
		if (mc.level?.dimension() == currentDimensionKey) {
			spawnMobOnLoadedSurface(mc)
		}
	}

	pendingMobSurfaceSnap?.let { (x, z) -> snapMobToLoadedSurface(mc, x, z) }

	// Transition as soon as all conditions are met (event-driven after MOB_SPAWN_TICK).
	if (++setupTick >= MOB_SPAWN_TICK && mobSpawned && pendingMobSurfaceSnap == null && poolBuildDone) {
		setupTick = 0; phase = AutoCapture.Phase.CAPTURING
	}
}

internal fun AutoCapture.tickCapturing(mc: Minecraft) {
	val player = mc.player ?: return
	val cfg = ConfigHolder.config
	clearMobFire(mc)

	// Keep the camera aligned while waiting for the previous frame to be consumed.
	applyRotation(mc)

	// Gate: wait until the render thread has consumed the previous pendingCapture.
	if (DatasetCapture.pendingCapture) return

	// Resolve the outcome of a fired capture: only advance the shot when an image was actually
	// written. A dropped frame (mob occluded / no usable box) retries this shot from a new angle.
	if (awaitingCaptureResult) {
		awaitingCaptureResult = false
		if (DatasetCapture.lastCaptureSucceeded) {
			if (firstShotMs == 0L) firstShotMs = System.currentTimeMillis()
			totalShots++
			completedWithoutImage = 0

			// Schedule a negative shot every 1/negativeFraction successful regular shots.
			val negInterval = if (cfg.negativeFraction > 0f) (1f / cfg.negativeFraction).toInt().coerceAtLeast(1) else 0
			if (negInterval > 0 && (shotCount + 1) % negInterval == 0) pendingNegativeShot = true

			advanceShotCount(mc)
		} else {
			bumpRetryOrRelocate(mc)
		}
		return
	}

	// Terrain wait: after a relocation snap the mob sits at (mobX, mobZ) - proceed as soon as that
	// chunk is loaded client-side instead of always burning the full settle. terrainWaitTick acts
	// only as a maximum cap so we never stall forever on a chunk that fails to arrive.
	if (terrainWaitTick > 0) {
		terrainWaitTick--
		if (terrainWaitTick > 0 &&
			mc.level?.isLoaded(net.minecraft.core.BlockPos(mobX.toInt(), mobY.toInt(), mobZ.toInt())) != true
		) return
		terrainWaitTick = 0
	}

	pendingMobSurfaceSnap?.let { (x, z) ->
		if (!snapMobToLoadedSurface(mc, x, z)) return
		terrainWaitTick = POST_SNAP_TICKS
		return
	}

	if (pendingNegativeShot) {
		pendingNegativeShot = false
		// Orbit to the opposite side and face away so the mob is behind the camera - by
		// construction the mob is out of frame, so this is always a valid background-only frame.
		val (angle, dist, heightOffset) = orbitParams(shotCount - 1)
		val oppositeAngle = angle + Math.PI
		val px = mobX + cos(oppositeAngle) * dist
		val pz = mobZ + sin(oppositeAngle) * dist
		val py = if (currentMobIsAquatic) {
			(mobY + heightOffset).coerceAtLeast(maxOf(mobY - 6.0, waterFloorY(mc, px.toInt(), pz.toInt()) + 0.4))
		} else {
			maxOf(safeSurfaceY(mc, px.toInt(), pz.toInt()), mobY) + heightOffset
		}
		// Face directly away from mob (toward-mob yaw + 180°).
		val dx = mobX - px; val dz = mobZ - pz; val h = sqrt(dx * dx + dz * dz)
		val awayYaw = (Math.toDegrees(atan2(-dx, dz)) + 180.0).toFloat()
		val awayPitch = if (h > 0.1) (Math.toDegrees(atan2((mobY + 1.0) - py, h))).toFloat() else 0f

		targetYaw = awayYaw; targetPitch = awayPitch
		player.snapTo(px, py, pz, targetYaw, targetPitch)
		val server = mc.singleplayerServer
		if (server != null) server.execute { serverPlayer(mc)?.teleportTo(px, py, pz) }
		else player.connection.sendCommand("tp @s ${px.fmt()} ${py.fmt()} ${pz.fmt()}")
		applyRotation(mc)

		DatasetCapture.pendingCaptureMetadata = CaptureMetadata(
			mobName = currentMobName, mobX = mobX, mobY = mobY, mobZ = mobZ,
			weather = currentWeather, timeOfDay = currentTime,
			shotIndex = shotCount, mobIndex = mobIndex,
			negative = true,
		)
		DatasetCapture.pendingCapture = true
		totalShots++
		return
	}

	updateMobPosition(mc)

	if (shotCount > 0 && shotCount % cfg.relocateEvery == 0 && lastRelocatedAtShot != shotCount) {
		lastRelocatedAtShot = shotCount
		val relocation = nextRelocation ?: chooseRelocation()
		pendingMobSurfaceSnap = relocation
		preloadPosition(mc, relocation.first, relocation.second)
		terrainWaitTick = RELOCATE_WAIT_TICKS
		nextRelocation = null
		return
	}

	currentWeather = weatherForShot(shotCount)
	currentTime = if (cfg.cameraJitterAndLighting) {
		(baseTime + shotCount * cfg.timePerShot + Random.nextLong(-2000, 2000)).rem(24000)
			.let { if (it < 0) it + 24000 else it }
	} else {
		(baseTime + shotCount * cfg.timePerShot) % 24000L
	}
	applyInstantTime(mc, currentTime)
	applyInstantWeather(mc, currentWeather)

	if ((shotCount + 1) % cfg.relocateEvery == 0 && nextRelocation == null) {
		nextRelocation = chooseRelocation()
		forceServerChunksAround(mc, nextRelocation!!.first.toInt(), nextRelocation!!.second.toInt())
	}

	val (angle, dist, heightOffset) = orbitParams(shotCount, completedWithoutImage)
	// Look-direction offsets so the mob is not always perfectly centred (applied AFTER the
	// exact-aim recompute below, otherwise recomputeAndApplyRotation would discard them).
	val jitterYaw =
		if (cfg.cameraJitterAndLighting) Random.nextFloat() * cfg.cameraJitterDegrees * 2 - cfg.cameraJitterDegrees else 0f
	val jitterPitch =
		if (cfg.cameraJitterAndLighting) Random.nextFloat() * cfg.cameraJitterDegrees - cfg.cameraJitterDegrees / 2 else 0f
	val lookOffsetYaw = if (cfg.lookOffsetDegrees > 0f)
		Random.nextFloat() * cfg.lookOffsetDegrees * 2 - cfg.lookOffsetDegrees else 0f
	val lookOffsetPitch = if (cfg.lookOffsetDegrees > 0f)
		Random.nextFloat() * cfg.lookOffsetDegrees - cfg.lookOffsetDegrees / 2 else 0f

	val px = mobX + cos(angle) * dist
	val pz = mobZ + sin(angle) * dist
	val py = if (currentMobIsAquatic) {
		(mobY + heightOffset).coerceAtLeast(maxOf(mobY - 6.0, waterFloorY(mc, px.toInt(), pz.toInt()) + 0.4))
	} else {
		maxOf(safeSurfaceY(mc, px.toInt(), pz.toInt()), mobY) + heightOffset
	}

	player.snapTo(px, py, pz, targetYaw, targetPitch)
	val server = mc.singleplayerServer
	if (server != null) server.execute { serverPlayer(mc)?.teleportTo(px, py, pz) }
	else player.connection.sendCommand("tp @s ${px.fmt()} ${py.fmt()} ${pz.fmt()}")

	// Aim exactly at the mob, then add the look-direction jitter/offset on top.
	recomputeAndApplyRotation(mc, jitterYaw + lookOffsetYaw, jitterPitch + lookOffsetPitch)

	// Skip if the chunk at the camera position isn't loaded client-side yet.
	if (mc.level?.isLoaded(net.minecraft.core.BlockPos(px.toInt(), py.toInt(), pz.toInt())) != true) {
		bumpRetryOrRelocate(mc)
		return
	}

	// Direct visibility raycast: only capture when the mob is genuinely unobstructed, otherwise
	// retry from a different angle (the glow silhouette renders through walls, so an occluded mob
	// would otherwise yield a frame where the mob isn't actually visible).
	if (!isVisible(mc)) {
		bumpRetryOrRelocate(mc)
		return
	}

	// Fire the capture and wait for the render thread to confirm an image was written before
	// advancing the shot counter (see the awaitingCaptureResult block at the top).
	DatasetCapture.pendingCaptureMetadata = CaptureMetadata(
		mobName = currentMobName, mobX = mobX, mobY = mobY, mobZ = mobZ,
		weather = currentWeather, timeOfDay = currentTime,
		shotIndex = shotCount, mobIndex = mobIndex,
	)
	DatasetCapture.lastCaptureSucceeded = false
	DatasetCapture.pendingCapture = true
	awaitingCaptureResult = true
}

/** Counts a failed capture attempt; after [AutoCapture.MAX_CAPTURE_RETRIES] in a row, relocates the mob. */
private fun AutoCapture.bumpRetryOrRelocate(mc: Minecraft) {
	completedWithoutImage++
	if (completedWithoutImage >= MAX_CAPTURE_RETRIES) {
		completedWithoutImage = 0
		val relocation = chooseRelocation()
		pendingMobSurfaceSnap = relocation
		preloadPosition(mc, relocation.first, relocation.second)
		terrainWaitTick = RELOCATE_WAIT_TICKS
	}
}

/** Increments shotCount and handles dimension-phase and mob transitions. */
private fun AutoCapture.advanceShotCount(mc: Minecraft) {
	val newCount = ++shotCount
	val entry = MOB_ENTRIES[mobIndex]
	val phaseLimit = shotLimitForPhase(dimensionPhaseIndex, entry.dimensions.size)

	// Pre-generate chunks for the next mob's base position while still capturing.
	val preloadAhead = 50
	if (newCount == phaseLimit - preloadAhead && dimensionPhaseIndex == entry.dimensions.size - 1) {
		val nextIdx = findNextMob(completedMobs + currentMobName)
		if (nextIdx != -1) {
			val nextEntry = MOB_ENTRIES[nextIdx]
			val nextDimKey = nextEntry.dimensions[0].levelKey
			val range = if (nextEntry.dimensions[0] == MobDimension.END) -150..150 else -500..500
			nextSetupBaseX = Random.nextInt(range.first, range.last + 1)
			nextSetupBaseZ = Random.nextInt(range.first, range.last + 1)
			preloadNextSetupPosition(mc, nextDimKey)
		}
	}

	if (newCount >= phaseLimit) {
		if (dimensionPhaseIndex < entry.dimensions.size - 1) {
			dimensionPhaseIndex++
			phase = AutoCapture.Phase.SETUP; setupTick = 0
		} else {
			ProgressStore.markCompleted(mc, currentMobName)
			completedMobs = completedMobs + currentMobName
			completedCount = completedMobs.size
			val nextIdx = findNextMob(completedMobs)
			if (nextIdx == -1) {
				mc.player?.sendSystemMessage(Component.literal("§a[YoloGen] §fAll ${MOB_ENTRIES.size} mobs completed! $totalShots total shots."))
				stop(mc)
			} else {
				mobIndex = nextIdx
				resetShotState()
				phase = AutoCapture.Phase.SETUP; setupTick = 0
			}
		}
	}
}
