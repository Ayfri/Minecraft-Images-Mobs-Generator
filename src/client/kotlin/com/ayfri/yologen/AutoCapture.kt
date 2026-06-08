package com.ayfri.yologen

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.*
import kotlin.math.*
import kotlin.random.Random

data object AutoCapture {
	internal const val BIOME_SCAN_RADIUS = 1000
	private const val BIOME_PREMAP_STEP = 64
	private const val MOB_TAG = "yologen_mob"
	internal const val MOB_SPAWN_TICK = 45
	private const val PRELOAD_HEIGHT = 200.0
	internal const val RELOCATE_EVERY = 10
	internal const val SETUP_WAIT_TICKS = 70
	internal const val SHOTS_CLEAR = 60
	internal const val SHOTS_PER_MOB = 100
	internal const val SHOTS_RAIN = 20
	internal const val TERRAIN_WAIT_TICKS = 30
	internal const val TIER_SIZE = 25
	internal const val TIME_PER_SHOT = 400L

	internal var currentMobName = ""
	internal var currentTime = 0L
	internal var currentWeather = "clear"
	internal var mobIndex = 0
	internal var phase = Phase.IDLE
	internal var running = false
	internal var setupTick = 0
	internal var shotCount = 0
	internal var terrainWaitTick = 0
	internal var totalShots = 0
	private var baseTime = 0L
	private var subTick = 0
	internal val hasNextRelocation get() = nextRelocation != null
	internal val relocationPoolSize get() = relocationPool.size

	private var baseX = 0
	private var baseZ = 0
	private var mobX = 0.0
	private var mobY = 64.0
	private var mobZ = 0.0
	private var safeY = 64.0

	private var currentMobRegName = ""
	private var lastRelocatedAtShot = -1
	private var mobSpawned = false
	private var nextRelocation: Pair<Double, Double>? = null
	private var pendingMobSurfaceSnap: Pair<Double, Double>? = null
	private var relocationCursor = 0
	private var relocationPool = emptyList<BiomeRelocation>()
	private var targetPitch = 0f
	private var targetYaw = 0f

	// Incremental pool build — scans already-loaded chunks to avoid blocking chunk generation.
	private const val POOL_BUILD_BATCH = 20
	private var poolBuildQueue = emptyList<PoolGridPos>()
	private var poolBuildIndex = 0
	private var poolBiomeMap = mutableMapOf<ResourceKey<Biome>, BiomeRelocation>()
	private var poolBuildDone = false

	private data class PoolGridPos(val worldX: Int, val worldZ: Int)

	internal enum class Phase { IDLE, SETUP, CAPTURING }

	private data class BiomeRelocation(val biome: ResourceKey<Biome>, val x: Double, val z: Double)

	private val mobTypes = YOLO_CLASS_MAP.keys.toList()

	private fun Double.fmt(decimals: Int = 2) = String.format(Locale.ROOT, "%.${decimals}f", this)
	private fun Int.toChunkCoord() = floorDiv(16)

	internal fun weatherForShot(idx: Int) = when {
		idx < SHOTS_CLEAR -> "clear"
		idx < SHOTS_CLEAR + SHOTS_RAIN -> "rain"
		else -> "thunder"
	}

	private fun loadedSurfaceY(mc: Minecraft, x: Int, z: Int): Double? {
		val level = mc.level ?: return null
		if (!level.isLoaded(BlockPos(x, 0, z))) return null
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z).toDouble()
	}

	private fun safeSurfaceY(mc: Minecraft, x: Int, z: Int): Double {
		val y = loadedSurfaceY(mc, x, z) ?: return safeY
		return if (y < 60.0) safeY else y
	}

	private fun serverLevel(mc: Minecraft): ServerLevel? {
		val server = mc.singleplayerServer ?: return null
		val dimension = mc.level?.dimension() ?: Level.OVERWORLD
		return server.getLevel(dimension)
	}

	private fun forceServerChunk(mc: Minecraft, x: Int, z: Int) {
		serverLevel(mc)?.chunkSource?.getChunk(x.toChunkCoord(), z.toChunkCoord(), ChunkStatus.FULL, true)
	}

	private fun applyInstantWeather(mc: Minecraft, weather: String) {
		val server = mc.singleplayerServer
		if (server == null) {
			mc.player?.connection?.sendCommand("weather $weather")
			return
		}

		when (weather) {
			"rain" -> server.setWeatherParameters(0, 24000, true, false)
			"thunder" -> server.setWeatherParameters(0, 24000, true, true)
			else -> server.setWeatherParameters(24000, 0, false, false)
		}
	}

	private fun applyInstantTime(mc: Minecraft, time: Long) {
		mc.player?.connection?.sendCommand("time set $time")
	}

	private fun fallbackRelocation() =
		(baseX + Random.nextInt(-20, 20)).toDouble() to (baseZ + Random.nextInt(-20, 20)).toDouble()

	private fun preloadPosition(mc: Minecraft, x: Double, z: Double) {
		forceServerChunk(mc, x.toInt(), z.toInt())
		mc.player?.connection?.sendCommand("tp @s ${x.fmt()} ${PRELOAD_HEIGHT.fmt()} ${z.fmt()}")
	}

	private fun startPoolBuild() {
		poolBiomeMap = mutableMapOf()
		poolBuildQueue = buildList {
			var dx = -BIOME_SCAN_RADIUS
			while (dx <= BIOME_SCAN_RADIUS) {
				var dz = -BIOME_SCAN_RADIUS
				while (dz <= BIOME_SCAN_RADIUS) {
					add(PoolGridPos(baseX + dx, baseZ + dz))
					dz += BIOME_PREMAP_STEP
				}
				dx += BIOME_PREMAP_STEP
			}
		}.shuffled()
		poolBuildIndex = 0
		poolBuildDone = false
	}

	// Called every tick during session — only reads already-loaded chunks, never forces generation.
	private fun advancePoolBuild(mc: Minecraft) {
		if (poolBuildDone) return
		val level = serverLevel(mc) ?: run { poolBuildDone = true; return }

		var done = 0
		while (done < POOL_BUILD_BATCH && poolBuildIndex < poolBuildQueue.size) {
			val pos = poolBuildQueue[poolBuildIndex]
			if (level.chunkSource.hasChunk(pos.worldX.toChunkCoord(), pos.worldZ.toChunkCoord())) {
				val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.worldX, pos.worldZ)
				val biome = level.getBiome(BlockPos(pos.worldX, y, pos.worldZ)).unwrapKey().orElse(null)
				if (biome != null) {
					val dx = (pos.worldX - baseX).toDouble()
					val dz = (pos.worldZ - baseZ).toDouble()
					val dist2 = dx * dx + dz * dz
					val existing = poolBiomeMap[biome]
					val existingDist2 = existing?.let {
						(it.x - baseX).pow(2) + (it.z - baseZ).pow(2)
					} ?: -1.0
					if (dist2 > existingDist2) poolBiomeMap[biome] =
						BiomeRelocation(biome, pos.worldX.toDouble(), pos.worldZ.toDouble())
				}
			}
			poolBuildIndex++
			done++
		}

		if (poolBuildIndex >= poolBuildQueue.size) {
			relocationPool = poolBiomeMap.values.shuffled()
			poolBiomeMap.clear()
			poolBuildDone = true
		}
	}

	private fun snapMobToLoadedSurface(mc: Minecraft, x: Double, z: Double): Boolean {
		val level = mc.level ?: return false
		forceServerChunk(mc, x.toInt(), z.toInt())
		if (!level.isLoaded(BlockPos(x.toInt(), 0, z.toInt()))) return false
		val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x.toInt(), z.toInt()).toDouble()
		val conn = mc.player?.connection ?: return false
		// TP to void first to skip death animation/particles, then re-summon.
		conn.sendCommand("tp @e[tag=$MOB_TAG] ~ -120 ~")
		conn.sendCommand("summon $currentMobRegName ${x.fmt()} ${y.fmt()} ${z.fmt()} {Invulnerable:1b,NoAI:1b,Tags:[\"$MOB_TAG\"]}")
		mobX = x; mobY = y; mobZ = z
		nextRelocation = null
		pendingMobSurfaceSnap = null
		safeY = y
		return true
	}

	// Tier-based orbit: 4×25 shots spanning 360° with even spacing + jitter per tier.
	// Tier 0: close-ground  Tier 1: medium-mid  Tier 2: far-high  Tier 3: top-down
	private fun orbitParams(shotIdx: Int): Triple<Double, Double, Double> {
		val tier = shotIdx / TIER_SIZE
		val baseAngle = (shotIdx % TIER_SIZE).toDouble() / TIER_SIZE * 2 * PI
		val angle = baseAngle + Random.nextDouble(-PI / TIER_SIZE, PI / TIER_SIZE)
		return when (tier) {
			0 -> Triple(angle, Random.nextDouble(3.5, 8.0), Random.nextDouble(0.4, 1.8))
			1 -> Triple(angle, Random.nextDouble(7.0, 13.0), Random.nextDouble(1.5, 5.0))
			2 -> Triple(angle, Random.nextDouble(11.0, 19.0), Random.nextDouble(4.0, 10.0))
			else -> Triple(angle, Random.nextDouble(2.0, 6.0), Random.nextDouble(9.0, 16.0))
		}
	}

	// Overrides interpolation and mouse input by setting both current and previous rotation.
	private fun applyRotation(mc: Minecraft) {
		val p = mc.player ?: return
		p.yRot = targetYaw; p.yRotO = targetYaw; p.setYHeadRot(targetYaw); p.setYBodyRot(targetYaw)
		p.xRot = targetPitch; p.xRotO = targetPitch
	}

	// Recomputes yaw/pitch toward the mob from the player's actual current position, then applies.
	// Use in subTick 1+ so the teleport has (partially) resolved before we lock the angle.
	private fun recomputeAndApplyRotation(mc: Minecraft) {
		val p = mc.player ?: return
		val dx = mobX - p.x
		val dy = (mobY + 1.0) - p.eyeY
		val dz = mobZ - p.z
		val h = sqrt(dx * dx + dz * dz)
		if (h < 0.01 && abs(dy) < 0.01) return   // degenerate case: on top of mob
		targetYaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
		targetPitch = (-Math.toDegrees(atan2(dy, h))).toFloat()
		applyRotation(mc)
	}

	// Lightweight fallback: picks a random far position at a different biome via sparse sampling.
	private fun findNewBiomePosition(mc: Minecraft): Pair<Int, Int>? {
		val level = mc.level ?: return null
		val currentKey =
			level.getBiome(BlockPos(mobX.toInt(), mobY.toInt(), mobZ.toInt())).unwrapKey().orElse(null) ?: return null

		repeat(32) {
			val x = baseX + Random.nextInt(-BIOME_SCAN_RADIUS, BIOME_SCAN_RADIUS + 1)
			val z = baseZ + Random.nextInt(-BIOME_SCAN_RADIUS, BIOME_SCAN_RADIUS + 1)
			val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
			if (level.getBiome(BlockPos(x, y, z)).unwrapKey().orElse(null) != currentKey) return x to z
		}
		return null
	}

	private fun chooseRelocation(mc: Minecraft): Pair<Double, Double> {
		val currentKey =
			mc.level?.getBiome(BlockPos(mobX.toInt(), mobY.toInt(), mobZ.toInt()))?.unwrapKey()?.orElse(null)

		if (relocationPool.isNotEmpty()) {
			repeat(relocationPool.size) {
				val candidate = relocationPool[relocationCursor % relocationPool.size]
				relocationCursor++
				if (candidate.biome != currentKey) return candidate.x to candidate.z
			}
		}

		return findNewBiomePosition(mc)?.let { (x, z) -> x.toDouble() to z.toDouble() } ?: fallbackRelocation()
	}

	// Latches onto the nearest living entity within 30 blocks to track gravity/movement.
	private fun updateMobPosition(mc: Minecraft) {
		val entities =
			mc.level?.entitiesForRendering()?.filterIsInstance<LivingEntity>()?.filter { it != mc.player } ?: return
		val nearest = entities.minByOrNull { (it.x - mobX).pow(2) + (it.z - mobZ).pow(2) } ?: return
		val dist2 = (nearest.x - mobX).pow(2) + (nearest.z - mobZ).pow(2)
		if (dist2 < 900.0) {
			mobX = nearest.x; mobY = nearest.y; mobZ = nearest.z
		}
	}

	private fun spawnMobOnLoadedSurface(mc: Minecraft): Boolean {
		val conn = mc.player?.connection ?: return false
		val surfY = loadedSurfaceY(mc, baseX, baseZ) ?: return false
		safeY = surfY
		conn.sendCommand("tp @s ${baseX.toDouble().fmt()} ${surfY.fmt()} ${baseZ.toDouble().fmt()}")

		val regName = BuiltInRegistries.ENTITY_TYPE.getKey(mobTypes.random()).toString()
		currentMobRegName = regName
		currentMobName = regName.substringAfter(':')
		mobX = baseX + Random.nextInt(-5, 5).toDouble()
		mobZ = baseZ + Random.nextInt(-5, 5).toDouble()
		mobY = loadedSurfaceY(mc, mobX.toInt(), mobZ.toInt()) ?: surfY
		conn.sendCommand("summon $regName ${mobX.fmt()} ${mobY.fmt()} ${mobZ.fmt()} {Invulnerable:1b,NoAI:1b,Tags:[\"$MOB_TAG\"]}")
		pendingMobSurfaceSnap = mobX to mobZ
		mobSpawned = true
		shotCount = 0; subTick = 0
		return true
	}

	// Raycasts from the camera eye to mob center; returns false if a solid block is in the way.
	private fun isVisible(mc: Minecraft): Boolean {
		val player = mc.player ?: return true
		val level = mc.level ?: return true
		val eye = player.eyePosition
		val target = Vec3(mobX, mobY + 1.0, mobZ)
		val hit = level.clip(ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
		return hit.type != HitResult.Type.BLOCK
	}

	fun register() {
		ClientTickEvents.END_CLIENT_TICK.register { mc ->
			if (running) tick(mc)
		}
	}

	private fun resetShotState() {
		shotCount = 0
		subTick = 0
		terrainWaitTick = 0
		lastRelocatedAtShot = -1
		mobSpawned = false
		nextRelocation = null
		pendingMobSurfaceSnap = null
		relocationCursor = 0
	}

	private fun resetMobState() {
		resetShotState()
		relocationPool = emptyList()
		poolBuildQueue = emptyList()
		poolBuildIndex = 0
		poolBiomeMap.clear()
		poolBuildDone = false
	}

	internal fun start(mc: Minecraft) {
		running = true; phase = Phase.SETUP; setupTick = 0
		mobIndex = 0; totalShots = 0
		safeY = mc.player?.y ?: 64.0
		DatasetCapture.autoMode = false
		baseX = Random.nextInt(-500, 500); baseZ = Random.nextInt(-500, 500)
		resetMobState()
		startPoolBuild()

		val thunderShots = SHOTS_PER_MOB - SHOTS_CLEAR - SHOTS_RAIN
		mc.player?.sendSystemMessage(Component.literal("§a[YoloGen] §fAuto-capture started §8- §7/yolostop  /yoloclear"))
		mc.player?.sendSystemMessage(Component.literal("§8  shots/mob: §f$SHOTS_PER_MOB §8| weather: §a${SHOTS_CLEAR}cl §9${SHOTS_RAIN}ra §5${thunderShots}th §8| time: §f+${TIME_PER_SHOT / 20}s/shot"))
		mc.player?.sendSystemMessage(Component.literal("§8  relocate every §f$RELOCATE_EVERY §8shots | biome pre-map §f±${BIOME_SCAN_RADIUS}blk §8step §f$BIOME_PREMAP_STEP §8| §f${MOB_TYPES.size} §8mob types"))
	}

	internal fun stop(mc: Minecraft) {
		running = false; phase = Phase.IDLE
		DatasetCapture.autoMode = true
		mc.player?.sendSystemMessage(Component.literal("§c[YoloGen] §fStopped - $mobIndex mobs, $totalShots shots captured"))
	}

	private fun tick(mc: Minecraft) {
		val player = mc.player ?: return
		val conn = player.connection

		if (phase == Phase.CAPTURING) {
			val icon = when (currentWeather) {
				"rain" -> "§9☂"; "thunder" -> "§5⚡"; else -> "§a☀"
			}
			val hour = (currentTime / 1000L + 6L) % 24L
			mc.gui.setOverlayMessage(
				Component.literal(
					"$icon §f$currentMobName §8| §7shot ${shotCount + 1}/$SHOTS_PER_MOB §8| §7mob $mobIndex/${MOB_TYPES.size} §8| §7%02d:00".format(
						hour
					)
				),
				false,
			)
		}

		when (phase) {
			Phase.IDLE -> {}

			Phase.SETUP -> {
				if (setupTick == 0) {
					conn.sendCommand("tp @e[tag=$MOB_TAG] ~ -120 ~")
					conn.sendCommand("kill @e[type=!player,distance=..200]")
					conn.sendCommand("gamemode spectator")
					baseTime = Random.nextLong(0, 24000)
					resetShotState()
					preloadPosition(mc, baseX.toDouble(), baseZ.toDouble())
				}

				advancePoolBuild(mc)

				if (setupTick >= MOB_SPAWN_TICK && !mobSpawned) {
					spawnMobOnLoadedSurface(mc)
				}

				pendingMobSurfaceSnap?.let { (x, z) ->
					snapMobToLoadedSurface(mc, x, z)
				}

				if (++setupTick >= SETUP_WAIT_TICKS && mobSpawned && pendingMobSurfaceSnap == null) {
					setupTick = 0; mobIndex++; phase = Phase.CAPTURING
				}
			}

			Phase.CAPTURING -> {
				pendingMobSurfaceSnap?.let { (x, z) ->
					if (!snapMobToLoadedSurface(mc, x, z)) return
					terrainWaitTick = 1
					return
				}

				// Wait for terrain to load after mob relocation before computing orbit.
				if (terrainWaitTick > 0) {
					terrainWaitTick--
					if (terrainWaitTick > 0) return
				}

				// Defensively re-lock rotation every tick in case a server packet reset it.
				if (subTick > 0) applyRotation(mc)

				when (subTick) {
					0 -> {
						updateMobPosition(mc)
						advancePoolBuild(mc)

						if (shotCount > 0 && shotCount % RELOCATE_EVERY == 0 && lastRelocatedAtShot != shotCount) {
							lastRelocatedAtShot = shotCount
							val relocation = nextRelocation ?: chooseRelocation(mc)
							pendingMobSurfaceSnap = relocation
							preloadPosition(mc, relocation.first, relocation.second)
							terrainWaitTick = TERRAIN_WAIT_TICKS
							return
						}

						currentWeather = weatherForShot(shotCount)
						currentTime = (baseTime + shotCount * TIME_PER_SHOT) % 24000L
						applyInstantTime(mc, currentTime)
						applyInstantWeather(mc, currentWeather)

						if ((shotCount + 1) % RELOCATE_EVERY == 0 && nextRelocation == null) {
							nextRelocation = chooseRelocation(mc)
							val (preloadX, preloadZ) = nextRelocation!!
							forceServerChunk(mc, preloadX.toInt(), preloadZ.toInt())
						}

						val (angle, dist, heightOffset) = orbitParams(shotCount)
						val px = mobX + cos(angle) * dist
						val pz = mobZ + sin(angle) * dist
						val py = maxOf(safeSurfaceY(mc, px.toInt(), pz.toInt()), mobY) + heightOffset
						val dx = mobX - px
						val dy = (mobY + 1.0) - py
						val dz = mobZ - pz
						val h = sqrt(dx * dx + dz * dz)
						targetYaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
						targetPitch = (-Math.toDegrees(atan2(dy, h))).toFloat()
						conn.sendCommand("tp @s ${px.fmt()} ${py.fmt()} ${pz.fmt()}")
						applyRotation(mc); subTick = 1
					}

					1 -> {
						recomputeAndApplyRotation(mc); subTick = 2
					}

					2 -> {
						recomputeAndApplyRotation(mc)
						// Only capture if no solid block occludes the mob.
						if (isVisible(mc)) {
							DatasetCapture.pendingCaptureMetadata = CaptureMetadata(
								mobName = currentMobName, mobX = mobX, mobY = mobY, mobZ = mobZ,
								weather = currentWeather, timeOfDay = currentTime,
								shotIndex = shotCount, mobIndex = mobIndex,
							)
							DatasetCapture.pendingCapture = true
							totalShots++
						}
						if (++shotCount >= SHOTS_PER_MOB) {
							phase = Phase.SETUP; setupTick = 0
						}
						subTick = 0
					}
				}
			}
		}
	}
}
