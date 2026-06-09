package com.ayfri.yologen

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.clock.WorldClocks
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.Relative
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.*
import kotlin.math.*
import kotlin.random.Random

internal enum class WeatherPhase(val label: String, val fraction: Float) {
	CLEAR("clear", 0.60f),
	RAIN("rain", 0.20f),
	THUNDER("thunder", 0.20f);

	fun shots(total: Int): Int = (total * fraction).roundToInt()
	val pct: Int get() = (fraction * 100).roundToInt()

	companion object {
		fun forShot(idx: Int, total: Int): WeatherPhase {
			var rem = idx
			for (phase in entries.dropLast(1)) {
				if (rem < phase.shots(total)) return phase
				rem -= phase.shots(total)
			}
			return entries.last()
		}
	}
}

// Extension so ClassMap stays import-free
private val MobDimension.levelKey: ResourceKey<Level>
	get() = when (this) {
		MobDimension.OVERWORLD -> Level.OVERWORLD
		MobDimension.NETHER -> Level.NETHER
		MobDimension.END -> Level.END
	}

private val MobDimension.label: String
	get() = when (this) {
		MobDimension.OVERWORLD -> "OW"
		MobDimension.NETHER -> "Nether"
		MobDimension.END -> "End"
	}

data object AutoCapture {
	internal const val BIOME_SCAN_RADIUS = 2000
	private const val BIOME_PREMAP_STEP = 64
	private const val MOB_TAG = "yologen_mob"
	internal const val MOB_SPAWN_TICK = 45
	private const val PRELOAD_CHUNK_RADIUS = 2
	private const val PRELOAD_HEIGHT = 200.0
	private const val NETHER_PRELOAD_HEIGHT = 80.0
	internal const val RELOCATE_EVERY = 10
	internal const val SETUP_WAIT_TICKS = 70
	internal const val SHOTS_PER_MOB = 200
	private const val TEMP_BUCKETS = 6
	internal const val TERRAIN_POST_SNAP_TICKS = 15
	internal const val TERRAIN_WAIT_TICKS = 30
	internal const val TIER_SIZE = 25
	internal const val TIME_PER_SHOT = 400L

	internal var completedCount = 0
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
	private var completedMobs = emptySet<String>()
	private var baseTime = 0L
	private var subTick = 0
	internal val hasNextRelocation get() = nextRelocation != null
	internal val relocationPoolSize get() = relocationPool.size

	// Dimension phase tracking - resets per mob, increments per dimension phase
	internal var currentDimension = MobDimension.OVERWORLD
	private var currentDimensionKey: ResourceKey<Level> = Level.OVERWORLD
	internal var dimensionPhaseIndex = 0

	private var baseX = 0
	private var baseZ = 0
	private var mobX = 0.0
	private var mobY = 64.0
	private var mobZ = 0.0
	private var safeY = 64.0
	private var savedFov = -1

	private var currentMobEntityType: net.minecraft.world.entity.EntityType<*>? = null
	private var currentMobRegName = ""
	private var lastRelocatedAtShot = -1
	private var mobSpawned = false
	private var nextRelocation: Pair<Double, Double>? = null
	private var pendingMobSurfaceSnap: Pair<Double, Double>? = null
	private var relocationCursor = 0

	@Volatile
	private var relocationPool = emptyList<BiomeRelocation>()
	private var targetBucket = 0
	private var targetPitch = 0f
	private var targetYaw = 0f

	// Incremental pool build
	private const val POOL_BUILD_BATCH = 20
	private var poolBuildQueue = emptyList<PoolGridPos>()
	private var poolBuildIndex = 0
	private var poolBiomeMap = mutableMapOf<ResourceKey<Biome>, BiomeRelocation>()

	@Volatile
	private var poolBuildDone = false

	private var poolPreloadIdx = 0

	private data class PoolGridPos(val worldX: Int, val worldZ: Int)

	internal enum class Phase { IDLE, SETUP, CAPTURING }

	private data class BiomeRelocation(val biome: ResourceKey<Biome>, val x: Double, val z: Double, val tempBucket: Int)

	private fun mobRegName(idx: Int) = BuiltInRegistries.ENTITY_TYPE.getKey(MOB_ENTRIES[idx].entityType).toString()
	private fun findNextMob(done: Set<String>) = MOB_ENTRIES.indexOfFirst {
		BuiltInRegistries.ENTITY_TYPE.getKey(it.entityType).toString().substringAfter(':') !in done
	}

	private fun Double.fmt(decimals: Int = 2) = String.format(Locale.ROOT, "%.${decimals}f", this)
	private fun Int.toChunkCoord() = floorDiv(16)

	// Shot count at which the current dimension phase ends
	private fun shotLimitForPhase(phaseIdx: Int, numDims: Int): Int {
		val base = SHOTS_PER_MOB / numDims
		return if (phaseIdx == numDims - 1) SHOTS_PER_MOB else base * (phaseIdx + 1)
	}

	// Temperature → climate bucket
	private fun tempBucket(temp: Float) = when {
		temp < 0.0f -> 0
		temp < 0.3f -> 1
		temp < 0.5f -> 2
		temp < 0.8f -> 3
		temp < 1.2f -> 4
		else -> 5
	}

	internal fun weatherForShot(idx: Int) = WeatherPhase.forShot(idx, SHOTS_PER_MOB).label

	// ── Direct server-side helpers ────────────────────────────────────────────

	private fun serverPlayer(mc: Minecraft): ServerPlayer? =
		mc.singleplayerServer?.playerList?.players?.firstOrNull()

	private fun forceServerChunksAround(mc: Minecraft, x: Int, z: Int, radius: Int = PRELOAD_CHUNK_RADIUS) {
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

	private fun advancePoolPreload(mc: Minecraft) {
		if (!poolBuildDone || relocationPool.isEmpty()) return
		if (poolPreloadIdx < relocationPool.size) {
			val entry = relocationPool[poolPreloadIdx++]
			forceServerChunksAround(mc, entry.x.toInt(), entry.z.toInt(), radius = 1)
		}
	}

	private fun applyInstantTime(mc: Minecraft, time: Long) {
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

	private fun applyInstantWeather(mc: Minecraft, weather: String) {
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

	private fun teleportPlayer(mc: Minecraft, x: Double, y: Double, z: Double) {
		val server = mc.singleplayerServer
		if (server != null) {
			server.execute {
				serverPlayer(mc)?.teleportTo(x, y, z)
			}
		} else {
			mc.player?.connection?.sendCommand("tp @s ${x.fmt()} ${y.fmt()} ${z.fmt()}")
		}
	}

	// Cross-dimension teleport - also used to stay in the same dim when already there.
	private fun teleportPlayerToDimension(mc: Minecraft, x: Double, y: Double, z: Double) {
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

	private fun preloadPosition(mc: Minecraft, x: Double, z: Double) {
		val preloadY = if (currentDimension == MobDimension.NETHER) NETHER_PRELOAD_HEIGHT else PRELOAD_HEIGHT
		forceServerChunksAround(mc, x.toInt(), z.toInt())
		teleportPlayerToDimension(mc, x, preloadY, z)
	}

	// Clears fire on all tagged entities - prevents visual burn for undead mobs in daylight.
	private fun clearMobFire(mc: Minecraft) {
		val server = mc.singleplayerServer ?: return
		server.execute {
			val sLevel = server.getLevel(currentDimensionKey) ?: return@execute
			sLevel.allEntities.filter { it.entityTags().contains(MOB_TAG) }.forEach { it.clearFire() }
		}
	}

	private fun spawnMobEntity(mc: Minecraft, x: Double, y: Double, z: Double) {
		val server = mc.singleplayerServer ?: return
		server.execute {
			val sLevel = server.getLevel(currentDimensionKey) ?: return@execute
			sLevel.allEntities.filter { it.entityTags().contains(MOB_TAG) }.forEach { it.discard() }
			val entity = currentMobEntityType?.create(sLevel, EntitySpawnReason.COMMAND) ?: return@execute
			entity.snapTo(x, y, z, 0f, 0f)
			entity.isInvulnerable = true
			entity.clearFire()
			(entity as? Mob)?.isNoAi = true
			(entity as? LivingEntity)?.addEffect(
				MobEffectInstance(
					MobEffects.FIRE_RESISTANCE,
					-1,
					0,
					false,
					false,
					false
				)
			)
			entity.addTag(MOB_TAG)
			sLevel.addFreshEntity(entity)
		}
	}

	// ── Pool build ────────────────────────────────────────────────────────────

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

	private fun advancePoolBuild(mc: Minecraft) {
		if (poolBuildDone) return
		val server = mc.singleplayerServer ?: run { poolBuildDone = true; return }
		server.execute {
			if (poolBuildDone) return@execute
			val level = server.getLevel(currentDimensionKey) ?: return@execute
			var done = 0
			while (done < POOL_BUILD_BATCH && poolBuildIndex < poolBuildQueue.size) {
				val pos = poolBuildQueue[poolBuildIndex]
				if (level.chunkSource.hasChunk(pos.worldX.toChunkCoord(), pos.worldZ.toChunkCoord())) {
					val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.worldX, pos.worldZ)
					val biomeHolder = level.getBiome(BlockPos(pos.worldX, y, pos.worldZ))
					val biomeKey = biomeHolder.unwrapKey().orElse(null)
					if (biomeKey != null) {
						val bucket = tempBucket(biomeHolder.value().baseTemperature)
						val dx = (pos.worldX - baseX).toDouble()
						val dz = (pos.worldZ - baseZ).toDouble()
						val dist2 = dx * dx + dz * dz
						val existing = poolBiomeMap[biomeKey]
						val existingDist2 = existing?.let { (it.x - baseX).pow(2) + (it.z - baseZ).pow(2) } ?: -1.0
						if (dist2 > existingDist2)
							poolBiomeMap[biomeKey] =
								BiomeRelocation(biomeKey, pos.worldX.toDouble(), pos.worldZ.toDouble(), bucket)
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
	}

	// ── Surface helpers ───────────────────────────────────────────────────────

	// In the Nether the heightmap returns the bedrock ceiling (Y=127), not the floor.
	// Scan downward from Y=100 to find the actual floor surface.
	private fun netherFloorY(mc: Minecraft, x: Int, z: Int): Double {
		val level = mc.level ?: return 40.0
		for (y in 100 downTo 5) {
			if (!level.getBlockState(BlockPos(x, y, z)).isAir && level.getBlockState(BlockPos(x, y + 1, z)).isAir) {
				return (y + 1).toDouble()
			}
		}
		return 40.0
	}

	private fun findClearPos(mc: Minecraft, centerX: Double, centerZ: Double, radius: Int = 30): Pair<Double, Double> {
		val level = mc.level ?: return centerX to centerZ
		for (i in 0 until 25) {
			val x = centerX + Random.nextInt(-radius, radius + 1)
			val z = centerZ + Random.nextInt(-radius, radius + 1)
			if (!level.isLoaded(BlockPos(x.toInt(), 0, z.toInt()))) continue
			if (currentDimension == MobDimension.NETHER) {
				// In Nether just verify the position has a solid floor
				val floorY = netherFloorY(mc, x.toInt(), z.toInt())
				if (floorY > 5.0) return x to z
			} else {
				val blocking = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x.toInt(), z.toInt())
				val noLeaves = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x.toInt(), z.toInt())
				if (blocking <= noLeaves + 1) return x to z
			}
		}
		return centerX to centerZ
	}

	private fun loadedSurfaceY(mc: Minecraft, x: Int, z: Int): Double? {
		val level = mc.level ?: return null
		if (!level.isLoaded(BlockPos(x, 0, z))) return null
		return if (currentDimension == MobDimension.NETHER) netherFloorY(mc, x, z)
		else level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z).toDouble()
	}

	// Uses MOTION_BLOCKING (includes leaves) so the camera sits above the canopy.
	private fun safeSurfaceY(mc: Minecraft, x: Int, z: Int): Double {
		val level = mc.level ?: return safeY
		if (!level.isLoaded(BlockPos(x, 0, z))) return safeY
		return if (currentDimension == MobDimension.NETHER) {
			netherFloorY(mc, x, z).also { if (it < 5.0) return safeY }
		} else {
			val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z).toDouble()
			if (y < 60.0) safeY else y
		}
	}

	private fun snapMobToLoadedSurface(mc: Minecraft, x: Double, z: Double): Boolean {
		val level = mc.level ?: return false
		forceServerChunksAround(mc, x.toInt(), z.toInt(), radius = 1)
		if (!level.isLoaded(BlockPos(x.toInt(), 0, z.toInt()))) return false
		val (clearX, clearZ) = findClearPos(mc, x, z)
		val y = loadedSurfaceY(mc, clearX.toInt(), clearZ.toInt()) ?: return false
		if (mc.singleplayerServer != null) {
			spawnMobEntity(mc, clearX, y, clearZ)
		} else {
			val conn = mc.player?.connection ?: return false
			conn.sendCommand("tp @e[tag=$MOB_TAG] ~ -120 ~")
			conn.sendCommand("summon $currentMobRegName ${clearX.fmt()} ${y.fmt()} ${clearZ.fmt()} {Invulnerable:1b,NoAI:1b,Tags:[\"$MOB_TAG\"],active_effects:[{id:\"minecraft:fire_resistance\",duration:-1,amplifier:0,ambient:0b,show_particles:0b,show_icon:0b}]}")
		}
		mobX = clearX; mobY = y; mobZ = clearZ
		nextRelocation = null
		pendingMobSurfaceSnap = null
		safeY = y
		return true
	}

	// ── Biome relocation ──────────────────────────────────────────────────────

	private fun fallbackRelocation() =
		(baseX + Random.nextInt(-20, 20)).toDouble() to (baseZ + Random.nextInt(-20, 20)).toDouble()

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
		val currentBiomeHolder = mc.level?.getBiome(BlockPos(mobX.toInt(), mobY.toInt(), mobZ.toInt()))
		val currentKey = currentBiomeHolder?.unwrapKey()?.orElse(null)
		val currentBucket = currentBiomeHolder?.value()?.baseTemperature?.let { tempBucket(it) } ?: -1

		if (relocationPool.isNotEmpty()) {
			for (offset in 0 until TEMP_BUCKETS) {
				val bucket = (targetBucket + offset) % TEMP_BUCKETS
				if (bucket == currentBucket) continue
				val candidates = relocationPool.filter { it.tempBucket == bucket && it.biome != currentKey }
				if (candidates.isNotEmpty()) {
					val pick = candidates[relocationCursor % candidates.size]
					relocationCursor++
					targetBucket = (bucket + 1) % TEMP_BUCKETS
					return pick.x to pick.z
				}
			}
			val anyOther = relocationPool.filter { it.biome != currentKey }
			if (anyOther.isNotEmpty()) {
				val pick = anyOther[relocationCursor % anyOther.size]
				relocationCursor++
				return pick.x to pick.z
			}
		}
		return findNewBiomePosition(mc)?.let { (x, z) -> x.toDouble() to z.toDouble() } ?: fallbackRelocation()
	}

	// ── Orbit + rotation ──────────────────────────────────────────────────────

	// Tier-based orbit. Angle uses RELOCATE_EVERY so each 10-shot burst sweeps ~360°.
	// Tier 0: close ground  Tier 1: medium low  Tier 2: far moderate
	// Tier 3: close side    Tier 4: medium mid  Tier 5: far low    Tier 6+: top-down
	private fun orbitParams(shotIdx: Int): Triple<Double, Double, Double> {
		val tier = shotIdx / TIER_SIZE
		// Each group of RELOCATE_EVERY shots covers a full 360° orbit
		val baseAngle = (shotIdx % RELOCATE_EVERY).toDouble() / RELOCATE_EVERY * 2 * PI
		val angle = baseAngle + Random.nextDouble(-PI / RELOCATE_EVERY, PI / RELOCATE_EVERY)
		return when (tier) {
			0 -> Triple(angle, Random.nextDouble(2.5, 5.5), Random.nextDouble(0.3, 1.5))
			1 -> Triple(angle, Random.nextDouble(5.0, 9.0), Random.nextDouble(1.0, 3.0))
			2 -> Triple(angle, Random.nextDouble(9.0, 15.0), Random.nextDouble(1.5, 4.0))
			3 -> Triple(angle, Random.nextDouble(3.0, 6.0), Random.nextDouble(0.3, 2.0))
			4 -> Triple(angle, Random.nextDouble(6.0, 11.0), Random.nextDouble(2.5, 6.0))
			5 -> Triple(angle, Random.nextDouble(10.0, 17.0), Random.nextDouble(0.5, 3.0))
			else -> Triple(angle, Random.nextDouble(2.0, 5.0), Random.nextDouble(8.0, 14.0))
		}
	}

	private fun applyRotation(mc: Minecraft) {
		val p = mc.player ?: return
		p.yRot = targetYaw; p.yRotO = targetYaw; p.setYHeadRot(targetYaw); p.setYBodyRot(targetYaw)
		p.xRot = targetPitch; p.xRotO = targetPitch
	}

	private fun recomputeAndApplyRotation(mc: Minecraft) {
		val p = mc.player ?: return
		val dx = mobX - p.x
		val dy = (mobY + 1.0) - p.eyeY
		val dz = mobZ - p.z
		val h = sqrt(dx * dx + dz * dz)
		if (h < 0.01 && abs(dy) < 0.01) return
		targetYaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
		targetPitch = (-Math.toDegrees(atan2(dy, h))).toFloat()
		applyRotation(mc)
	}

	private fun updateMobPosition(mc: Minecraft) {
		val entities =
			mc.level?.entitiesForRendering()?.filterIsInstance<LivingEntity>()?.filter { it != mc.player } ?: return
		val nearest = entities.minByOrNull { (it.x - mobX).pow(2) + (it.z - mobZ).pow(2) } ?: return
		val dist2 = (nearest.x - mobX).pow(2) + (nearest.z - mobZ).pow(2)
		if (dist2 < 900.0) {
			mobX = nearest.x; mobY = nearest.y; mobZ = nearest.z
		}
	}

	private fun isVisible(mc: Minecraft): Boolean {
		val player = mc.player ?: return true
		val level = mc.level ?: return true
		val eye = player.eyePosition
		val target = Vec3(mobX, mobY + 1.0, mobZ)
		val hit = level.clip(ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
		return hit.type != HitResult.Type.BLOCK
	}

	// ── Mob spawn ─────────────────────────────────────────────────────────────

	private fun spawnMobOnLoadedSurface(mc: Minecraft): Boolean {
		val surfY = loadedSurfaceY(mc, baseX, baseZ) ?: return false
		safeY = surfY
		teleportPlayer(mc, baseX.toDouble(), surfY, baseZ.toDouble())

		val entry = MOB_ENTRIES[mobIndex]
		currentMobEntityType = entry.entityType
		currentMobRegName = BuiltInRegistries.ENTITY_TYPE.getKey(entry.entityType).toString()
		currentMobName = currentMobRegName.substringAfter(':')
		val (clearMobX, clearMobZ) = findClearPos(mc, baseX.toDouble(), baseZ.toDouble())
		mobX = clearMobX
		mobZ = clearMobZ
		mobY = loadedSurfaceY(mc, mobX.toInt(), mobZ.toInt()) ?: surfY

		if (mc.singleplayerServer != null) {
			spawnMobEntity(mc, mobX, mobY, mobZ)
		} else {
			mc.player?.connection?.sendCommand(
				"summon $currentMobRegName ${mobX.fmt()} ${mobY.fmt()} ${mobZ.fmt()} {Invulnerable:1b,NoAI:1b,Tags:[\"$MOB_TAG\"],active_effects:[{id:\"minecraft:fire_resistance\",duration:-1,amplifier:0,ambient:0b,show_particles:0b,show_icon:0b}]}"
			)
		}
		pendingMobSurfaceSnap = mobX to mobZ
		mobSpawned = true
		subTick = 0
		return true
	}

	// ── Lifecycle ─────────────────────────────────────────────────────────────

	fun register() {
		ClientTickEvents.END_CLIENT_TICK.register { mc ->
			if (running) tick(mc)
		}
	}

	// Full reset for a new mob - also resets shotCount and dimension phase.
	private fun resetShotState() {
		shotCount = 0
		subTick = 0
		terrainWaitTick = 0
		lastRelocatedAtShot = -1
		mobSpawned = false
		nextRelocation = null
		pendingMobSurfaceSnap = null
		relocationCursor = 0
		targetBucket = 0
		currentMobEntityType = null
		dimensionPhaseIndex = 0
	}

	private fun resetMobState() {
		resetShotState()
		relocationPool = emptyList()
		poolBuildQueue = emptyList()
		poolBuildIndex = 0
		poolBiomeMap.clear()
		poolBuildDone = false
		poolPreloadIdx = 0
	}

	// Resets spatial state for a new dimension phase while preserving shotCount and mob identity.
	private fun resetDimPhaseState() {
		subTick = 0
		terrainWaitTick = 0
		lastRelocatedAtShot = -1
		mobSpawned = false
		nextRelocation = null
		pendingMobSurfaceSnap = null
		relocationCursor = 0
		targetBucket = 0
		relocationPool = emptyList()
		poolBuildQueue = emptyList()
		poolBuildIndex = 0
		poolBiomeMap.clear()
		poolBuildDone = false
		poolPreloadIdx = 0
	}

	// Base position range: End main island is limited; Nether and OW can roam wider.
	private val basePosRange: IntRange get() = if (currentDimension == MobDimension.END) -150..150 else -500..500

	internal fun start(mc: Minecraft) {
		completedMobs = ProgressStore.load(mc)
		val nextIdx = findNextMob(completedMobs)
		if (nextIdx == -1) {
			mc.player?.sendSystemMessage(Component.literal("§a[YoloGen] §fAll ${MOB_ENTRIES.size} mobs already captured. Use §7/yoloclear §fto reset."))
			return
		}
		mobIndex = nextIdx
		completedCount = completedMobs.size
		running = true; phase = Phase.SETUP; setupTick = 0
		totalShots = completedMobs.size * SHOTS_PER_MOB
		safeY = mc.player?.y ?: 64.0
		DatasetCapture.autoMode = false
		DatasetCapture.resumeFrom(ProgressStore.resumeCaptureIndex(mc))
		resetMobState()
		savedFov = mc.options.fov().get()
		mc.options.fov().set(70)

		val wDesc = WeatherPhase.entries.joinToString(" ") { "${it.pct}%${it.label.first()}" }
		val resumeMsg =
			if (completedMobs.isNotEmpty()) " §8(resuming from §f${mobRegName(nextIdx).substringAfter(':')}§8, ${completedMobs.size}/${MOB_ENTRIES.size} done)" else ""
		mc.player?.sendSystemMessage(Component.literal("§a[YoloGen] §fAuto-capture started$resumeMsg §8- §7/yolostop  /yoloclear"))
		mc.player?.sendSystemMessage(Component.literal("§8  shots/mob: §f$SHOTS_PER_MOB §8| weather: §f$wDesc §8| time: §f+${TIME_PER_SHOT / 20}s/shot"))
		mc.player?.sendSystemMessage(Component.literal("§8  relocate every §f$RELOCATE_EVERY §8shots | biome pre-map §f±${BIOME_SCAN_RADIUS}blk §8step §f$BIOME_PREMAP_STEP §8| §f${MOB_ENTRIES.size} §8mob types"))

		val server = mc.singleplayerServer
		if (server != null) {
			server.execute {
				server.commands.performPrefixedCommand(
					server.createCommandSourceStack(),
					"gamerule doMobSpawning false"
				)
				server.commands.performPrefixedCommand(
					server.createCommandSourceStack(),
					"gamerule doDaylightCycle false"
				)
				server.commands.performPrefixedCommand(
					server.createCommandSourceStack(),
					"gamerule doWeatherCycle false"
				)
			}
		} else {
			mc.player?.connection?.sendCommand("gamerule doMobSpawning false")
			mc.player?.connection?.sendCommand("gamerule doDaylightCycle false")
			mc.player?.connection?.sendCommand("gamerule doWeatherCycle false")
		}
	}

	internal fun onClear() {
		completedMobs = emptySet()
		completedCount = 0
	}

	internal fun stop(mc: Minecraft) {
		running = false; phase = Phase.IDLE
		DatasetCapture.autoMode = true
		if (savedFov != -1) {
			mc.options.fov().set(savedFov); savedFov = -1
		}
		mc.player?.sendSystemMessage(Component.literal("§c[YoloGen] §fStopped - $mobIndex mobs, $totalShots shots captured"))

		val server = mc.singleplayerServer
		if (server != null) {
			server.execute {
				server.commands.performPrefixedCommand(server.createCommandSourceStack(), "gamerule doMobSpawning true")
				server.commands.performPrefixedCommand(
					server.createCommandSourceStack(),
					"gamerule doDaylightCycle true"
				)
				server.commands.performPrefixedCommand(
					server.createCommandSourceStack(),
					"gamerule doWeatherCycle true"
				)
			}
		} else {
			mc.player?.connection?.sendCommand("gamerule doMobSpawning true")
			mc.player?.connection?.sendCommand("gamerule doDaylightCycle true")
			mc.player?.connection?.sendCommand("gamerule doWeatherCycle true")
		}
	}

	// ── Main tick ─────────────────────────────────────────────────────────────

	private fun tick(mc: Minecraft) {
		val player = mc.player ?: return
		val conn = player.connection

		if (phase == Phase.CAPTURING) {
			val dimLabel = if (currentDimension != MobDimension.OVERWORLD) " §8[§7${currentDimension.label}§8]" else ""
			val icon = when (currentWeather) {
				"rain" -> "§9☂"; "thunder" -> "§5⚡"; else -> "§a☀"
			}
			val hour = (currentTime / 1000L + 6L) % 24L
			mc.gui.setOverlayMessage(
				Component.literal(
					"$icon §f$currentMobName$dimLabel §8| §7shot ${shotCount + 1}/$SHOTS_PER_MOB §8| §7mob ${completedCount + 1}/${MOB_ENTRIES.size} §8| §7%02d:00".format(
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
					// Set dimension for this phase
					val entry = MOB_ENTRIES[mobIndex]
					currentDimension = entry.dimensions[dimensionPhaseIndex]
					currentDimensionKey = currentDimension.levelKey

					val server = mc.singleplayerServer
					if (server != null) {
						val dimKey = currentDimensionKey
						server.execute {
							val sLevel = server.getLevel(dimKey) ?: return@execute
							sLevel.allEntities.filter { it.entityTags().contains(MOB_TAG) }.forEach { it.discard() }
							// Also kill OW entities when switching dimensions
							server.getLevel(Level.OVERWORLD)?.allEntities
								?.filter { it.entityTags().contains(MOB_TAG) }
								?.forEach { it.discard() }
							serverPlayer(mc)?.gameMode?.changeGameModeForPlayer(GameType.SPECTATOR)
						}
					} else {
						conn.sendCommand("kill @e[type=!player]")
						conn.sendCommand("gamemode spectator")
					}

					baseTime = Random.nextLong(0, 24000)
					val range = basePosRange
					baseX = Random.nextInt(range.first, range.last + 1)
					baseZ = Random.nextInt(range.first, range.last + 1)

					if (dimensionPhaseIndex == 0) resetMobState() else resetDimPhaseState()

					startPoolBuild()
					preloadPosition(mc, baseX.toDouble(), baseZ.toDouble())

					if (dimensionPhaseIndex > 0) {
						mc.player?.sendSystemMessage(
							Component.literal("§e[YoloGen] §f$currentMobName §8→ §7${currentDimension.label} §8(phase ${dimensionPhaseIndex + 1}/${entry.dimensions.size})")
						)
					}
				}

				advancePoolBuild(mc)

				// Wait for the client level to switch to the target dimension before spawning
				if (setupTick >= MOB_SPAWN_TICK && !mobSpawned) {
					if (mc.level?.dimension() == currentDimensionKey) {
						spawnMobOnLoadedSurface(mc)
					}
				}

				pendingMobSurfaceSnap?.let { (x, z) ->
					snapMobToLoadedSurface(mc, x, z)
				}

				if (++setupTick >= SETUP_WAIT_TICKS && mobSpawned && pendingMobSurfaceSnap == null) {
					setupTick = 0; phase = Phase.CAPTURING
				}
			}

			Phase.CAPTURING -> {
				clearMobFire(mc)

				pendingMobSurfaceSnap?.let { (x, z) ->
					if (!snapMobToLoadedSurface(mc, x, z)) return
					terrainWaitTick = TERRAIN_POST_SNAP_TICKS
					return
				}

				if (terrainWaitTick > 0) {
					terrainWaitTick--
					if (terrainWaitTick > 0) return
				}

				if (subTick > 0) applyRotation(mc)

				when (subTick) {
					0 -> {
						updateMobPosition(mc)
						advancePoolBuild(mc)
						advancePoolPreload(mc)

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
							forceServerChunksAround(mc, preloadX.toInt(), preloadZ.toInt())
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
						teleportPlayer(mc, px, py, pz)
						applyRotation(mc); subTick = 1
					}

					1 -> {
						recomputeAndApplyRotation(mc); subTick = 2
					}

					2 -> {
						recomputeAndApplyRotation(mc)
						if (isVisible(mc)) {
							DatasetCapture.pendingCaptureMetadata = CaptureMetadata(
								mobName = currentMobName, mobX = mobX, mobY = mobY, mobZ = mobZ,
								weather = currentWeather, timeOfDay = currentTime,
								shotIndex = shotCount, mobIndex = mobIndex,
							)
							DatasetCapture.pendingCapture = true
							totalShots++
						}

						val newCount = ++shotCount
						val entry = MOB_ENTRIES[mobIndex]
						val phaseLimit = shotLimitForPhase(dimensionPhaseIndex, entry.dimensions.size)

						if (newCount >= phaseLimit) {
							if (dimensionPhaseIndex < entry.dimensions.size - 1) {
								// Advance to next dimension phase for this mob
								dimensionPhaseIndex++
								phase = Phase.SETUP; setupTick = 0
							} else {
								// All dimension phases done - mob complete
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
									phase = Phase.SETUP; setupTick = 0
								}
							}
						}
						subTick = 0
					}
				}
			}
		}
	}
}
