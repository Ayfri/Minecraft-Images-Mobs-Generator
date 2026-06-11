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

	if (setupTick >= MOB_SPAWN_TICK && !mobSpawned) {
		if (mc.level?.dimension() == currentDimensionKey) {
			spawnMobOnLoadedSurface(mc)
		}
	}

	pendingMobSurfaceSnap?.let { (x, z) -> snapMobToLoadedSurface(mc, x, z) }

	if (++setupTick >= SETUP_WAIT_TICKS && mobSpawned && pendingMobSurfaceSnap == null && poolBuildDone) {
		setupTick = 0; phase = AutoCapture.Phase.CAPTURING
	}
}

internal fun AutoCapture.tickCapturing(mc: Minecraft) {
	val player = mc.player ?: return
	val cfg = ConfigHolder.config
	clearMobFire(mc)

	pendingMobSurfaceSnap?.let { (x, z) ->
		if (!snapMobToLoadedSurface(mc, x, z)) return
		terrainWaitTick = POST_SNAP_TICKS
		return
	}

	if (terrainWaitTick > 0) {
		if (--terrainWaitTick > 0) return
	}

	if (subTick > 0) applyRotation(mc)

	when (subTick) {
		0 -> {
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

			val (angle, dist, heightOffset) = orbitParams(shotCount)
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
				// Orbit directly around mobY so the camera can be underwater
				(mobY + heightOffset).coerceAtLeast(mobY - 6.0)
			} else {
				maxOf(safeSurfaceY(mc, px.toInt(), pz.toInt()), mobY) + heightOffset
			}

			val dx = mobX - px
			val dy = (mobY + 1.0) - py
			val dz = mobZ - pz
			val h = sqrt(dx * dx + dz * dz)
			targetYaw = Math.toDegrees(atan2(-dx, dz)).toFloat() + jitterYaw + lookOffsetYaw
			targetPitch = (-Math.toDegrees(atan2(dy, h))).toFloat() + jitterPitch + lookOffsetPitch

			player.snapTo(px, py, pz, targetYaw, targetPitch)
			val server = mc.singleplayerServer
			if (server != null) server.execute { serverPlayer(mc)?.teleportTo(px, py, pz) }
			else player.connection.sendCommand("tp @s ${px.fmt()} ${py.fmt()} ${pz.fmt()}")

			applyRotation(mc)
			subTick = 1
		}

		1 -> {
			recomputeAndApplyRotation(mc)

			val visOk = isVisible(mc)
			if (visOk || completedWithoutImage >= 4) {
				if (!visOk) completedWithoutImage = 0
				DatasetCapture.pendingCaptureMetadata = CaptureMetadata(
					mobName = currentMobName, mobX = mobX, mobY = mobY, mobZ = mobZ,
					weather = currentWeather, timeOfDay = currentTime,
					shotIndex = shotCount, mobIndex = mobIndex,
				)
				DatasetCapture.pendingCapture = true
				totalShots++
				completedWithoutImage = 0
			} else {
				completedWithoutImage++
				subTick = 0
				return
			}

			val newCount = ++shotCount
			val entry = MOB_ENTRIES[mobIndex]
			val phaseLimit = shotLimitForPhase(dimensionPhaseIndex, entry.dimensions.size)

			// Pre-generate chunks for the next mob's base position while still capturing,
			// so terrain is ready before the next setup phase begins.
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
			subTick = 0
		}
	}
}
