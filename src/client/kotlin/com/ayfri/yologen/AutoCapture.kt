package com.ayfri.yologen

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.clock.WorldClocks
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
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

data object AutoCapture {
	internal const val BIOME_SCAN_RADIUS = 2000
	private const val BIOME_PREMAP_STEP = 64
	private const val MOB_TAG = "yologen_mob"
	internal const val MOB_SPAWN_TICK = 45
	private const val PRELOAD_CHUNK_RADIUS = 2
	private const val PRELOAD_HEIGHT = 200.0
	internal const val RELOCATE_EVERY = 10
	internal const val SETUP_WAIT_TICKS = 70
	internal const val SHOTS_PER_MOB = 200
	private const val TEMP_BUCKETS = 6
	internal const val TERRAIN_POST_SNAP_TICKS = 15
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
	private var savedFov = -1

	private var currentMobEntityType: net.minecraft.world.entity.EntityType<*>? = null
	private var currentMobRegName = ""
	private var lastRelocatedAtShot = -1
	private var mobSpawned = false
	private var nextRelocation: Pair<Double, Double>? = null
	private var pendingMobSurfaceSnap: Pair<Double, Double>? = null
	private var relocationCursor = 0
	private var relocationPool = emptyList<BiomeRelocation>()
	private var targetBucket = 0
	private var targetPitch = 0f
	private var targetYaw = 0f

	// Incremental pool build - scans already-loaded chunks to avoid blocking chunk generation.
	private const val POOL_BUILD_BATCH = 20
	private var poolBuildQueue = emptyList<PoolGridPos>()
	private var poolBuildIndex = 0
	private var poolBiomeMap = mutableMapOf<ResourceKey<Biome>, BiomeRelocation>()
	private var poolBuildDone = false

	// Background pool preloader — warms up server chunks for all relocation destinations.
	private var poolPreloadIdx = 0

	private data class PoolGridPos(val worldX: Int, val worldZ: Int)

	internal enum class Phase { IDLE, SETUP, CAPTURING }

	private data class BiomeRelocation(val biome: ResourceKey<Biome>, val x: Double, val z: Double, val tempBucket: Int)

	private val mobTypes = YOLO_CLASS_MAP.keys.toList()

	private fun Double.fmt(decimals: Int = 2) = String.format(Locale.ROOT, "%.${decimals}f", this)
	private fun Int.toChunkCoord() = floorDiv(16)

	// Temperature → climate bucket: 0=frozen, 1=cold, 2=cool, 3=temperate, 4=warm, 5=hot
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

	private fun serverLevel(mc: Minecraft): ServerLevel? {
		val server = mc.singleplayerServer ?: return null
		val dimension = mc.level?.dimension() ?: Level.OVERWORLD
		return server.getLevel(dimension)
	}

	// Forces server chunks asynchronously — posts to server thread to avoid client-tick freeze.
	private fun forceServerChunksAround(mc: Minecraft, x: Int, z: Int, radius: Int = PRELOAD_CHUNK_RADIUS) {
		val server = mc.singleplayerServer ?: return
		val dimension = mc.level?.dimension() ?: Level.OVERWORLD
		val cx = x.toChunkCoord()
		val cz = z.toChunkCoord()
		server.execute {
			val sLevel = server.getLevel(dimension) ?: return@execute
			for (dx in -radius..radius)
				for (dz in -radius..radius)
					sLevel.chunkSource.getChunk(cx + dx, cz + dz, ChunkStatus.FULL, true)
		}
	}

	// Warms up server chunks for the next unvisited pool entry — 1 per tick, radius=1.
	private fun advancePoolPreload(mc: Minecraft) {
		if (!poolBuildDone || relocationPool.isEmpty()) return
		if (poolPreloadIdx < relocationPool.size) {
			val entry = relocationPool[poolPreloadIdx++]
			forceServerChunksAround(mc, entry.x.toInt(), entry.z.toInt(), radius = 1)
		}
	}

	// Sets day-time via ServerClockManager - the new 26.1 time system.
	private fun applyInstantTime(mc: Minecraft, time: Long) {
		val server = mc.singleplayerServer
		if (server != null) {
			val clockManager = server.clockManager()
			val clockHolder = server.registryAccess()
				.lookupOrThrow(Registries.WORLD_CLOCK)
				.getOrThrow(WorldClocks.OVERWORLD)
			val current = clockManager.getTotalTicks(clockHolder)
			val aligned = (current / 24000L) * 24000L + time
			clockManager.setTotalTicks(clockHolder, aligned)
			return
		}
		mc.player?.connection?.sendCommand("time set $time")
	}

	private fun applyInstantWeather(mc: Minecraft, weather: String) {
		val server = mc.singleplayerServer
		if (server != null) {
			when (weather) {
				"rain" -> server.setWeatherParameters(0, 24000, true, false)
				"thunder" -> server.setWeatherParameters(0, 24000, true, true)
				else -> server.setWeatherParameters(24000, 0, false, false)
			}
			return
		}
		mc.player?.connection?.sendCommand("weather $weather")
	}

	// Teleports the server-side player (preserves rotation, produces no log).
	private fun teleportPlayer(mc: Minecraft, x: Double, y: Double, z: Double) {
		serverPlayer(mc)?.teleportTo(x, y, z)
			?: mc.player?.connection?.sendCommand("tp @s ${x.fmt()} ${y.fmt()} ${z.fmt()}")
	}

	// Teleports player to PRELOAD_HEIGHT above target and forces surrounding chunks.
	private fun preloadPosition(mc: Minecraft, x: Double, z: Double) {
		forceServerChunksAround(mc, x.toInt(), z.toInt())
		teleportPlayer(mc, x, PRELOAD_HEIGHT, z)
	}

	// Discards any tagged mob and spawns a fresh one at (x, y, z) - no /summon command or log.
	// Must run on the server thread (C2ME enforces thread-safe entity management).
	private fun spawnMobEntity(mc: Minecraft, sLevel: ServerLevel, x: Double, y: Double, z: Double) {
		val server = mc.singleplayerServer ?: return
		server.execute {
			sLevel.allEntities.filter { it.entityTags().contains(MOB_TAG) }.forEach { it.discard() }
			val entity = currentMobEntityType?.create(sLevel, EntitySpawnReason.COMMAND) ?: return@execute
			entity.snapTo(x, y, z, 0f, 0f)
			entity.isInvulnerable = true
			entity.clearFire()
			(entity as? Mob)?.isNoAi = true
			(entity as? LivingEntity)?.addEffect(MobEffectInstance(MobEffects.FIRE_RESISTANCE, -1, 0, false, false, false))
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

	// Called every tick - only reads already-loaded chunks, never forces generation.
	private fun advancePoolBuild(mc: Minecraft) {
		if (poolBuildDone) return
		val level = serverLevel(mc) ?: run { poolBuildDone = true; return }

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

	// ── Surface helpers ───────────────────────────────────────────────────────

	// Returns a position near (centerX, centerZ) that is not under a tree canopy.
	private fun findClearPos(mc: Minecraft, centerX: Double, centerZ: Double, radius: Int = 30): Pair<Double, Double> {
		val level = mc.level ?: return centerX to centerZ
		for (i in 0 until 25) {
			val x = centerX + Random.nextInt(-radius, radius + 1)
			val z = centerZ + Random.nextInt(-radius, radius + 1)
			if (!level.isLoaded(BlockPos(x.toInt(), 0, z.toInt()))) continue
			val blocking = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x.toInt(), z.toInt())
			val noLeaves = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x.toInt(), z.toInt())
			if (blocking <= noLeaves + 1) return x to z
		}
		return centerX to centerZ
	}

	private fun loadedSurfaceY(mc: Minecraft, x: Int, z: Int): Double? {
		val level = mc.level ?: return null
		if (!level.isLoaded(BlockPos(x, 0, z))) return null
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z).toDouble()
	}

	// Uses MOTION_BLOCKING (includes leaves) so the camera sits above the canopy, not inside it.
	private fun safeSurfaceY(mc: Minecraft, x: Int, z: Int): Double {
		val level = mc.level ?: return safeY
		if (!level.isLoaded(BlockPos(x, 0, z))) return safeY
		val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z).toDouble()
		return if (y < 60.0) safeY else y
	}

	// Snaps the mob to the loaded client surface. Returns false if chunk not ready yet.
	private fun snapMobToLoadedSurface(mc: Minecraft, x: Double, z: Double): Boolean {
		val level = mc.level ?: return false
		forceServerChunksAround(mc, x.toInt(), z.toInt(), radius = 1)
		if (!level.isLoaded(BlockPos(x.toInt(), 0, z.toInt()))) return false
		val (clearX, clearZ) = findClearPos(mc, x, z)
		val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, clearX.toInt(), clearZ.toInt()).toDouble()
		val sLevel = serverLevel(mc)
		if (sLevel != null) {
			spawnMobEntity(mc, sLevel, clearX, y, clearZ)
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
			// Cycle through climate buckets, always skipping the current zone
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

	// Tier-based orbit: tiers 0-5 favour side/angled views; tiers 6+ (else) are top-down.
	// With TIER_SIZE=25 and SHOTS_PER_MOB=200: 150 side shots (75%) + 50 top-down (25%).
	// Tier 0: close ground  Tier 1: medium low  Tier 2: far moderate
	// Tier 3: close side    Tier 4: medium mid  Tier 5: far low    Tier 6+: top-down
	private fun orbitParams(shotIdx: Int): Triple<Double, Double, Double> {
		val tier = shotIdx / TIER_SIZE
		val baseAngle = (shotIdx % TIER_SIZE).toDouble() / TIER_SIZE * 2 * PI
		val angle = baseAngle + Random.nextDouble(-PI / TIER_SIZE, PI / TIER_SIZE)
		return when (tier) {
			0 -> Triple(angle, Random.nextDouble(2.5, 5.5), Random.nextDouble(0.3, 1.5))   // close, almost horizontal
			1 -> Triple(angle, Random.nextDouble(5.0, 9.0), Random.nextDouble(1.0, 3.0))   // medium, slight angle
			2 -> Triple(angle, Random.nextDouble(9.0, 15.0), Random.nextDouble(1.5, 4.0))  // far, moderate angle
			3 -> Triple(angle, Random.nextDouble(3.0, 6.0), Random.nextDouble(0.3, 2.0))   // close, side repeat
			4 -> Triple(angle, Random.nextDouble(6.0, 11.0), Random.nextDouble(2.5, 6.0))  // medium, mid-height
			5 -> Triple(angle, Random.nextDouble(10.0, 17.0), Random.nextDouble(0.5, 3.0)) // far, eye-level
			else -> Triple(angle, Random.nextDouble(2.0, 5.0), Random.nextDouble(8.0, 14.0)) // top-down
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

		val mobType = mobTypes.random()
		currentMobEntityType = mobType
		currentMobRegName = BuiltInRegistries.ENTITY_TYPE.getKey(mobType).toString()
		currentMobName = currentMobRegName.substringAfter(':')
		val (clearMobX, clearMobZ) = findClearPos(mc, baseX.toDouble(), baseZ.toDouble())
		mobX = clearMobX
		mobZ = clearMobZ
		mobY = loadedSurfaceY(mc, mobX.toInt(), mobZ.toInt()) ?: surfY

		val sLevel = serverLevel(mc)
		if (sLevel != null) {
			spawnMobEntity(mc, sLevel, mobX, mobY, mobZ)
		} else {
			mc.player?.connection?.sendCommand(
				"summon $currentMobRegName ${mobX.fmt()} ${mobY.fmt()} ${mobZ.fmt()} {Invulnerable:1b,NoAI:1b,Tags:[\"$MOB_TAG\"],active_effects:[{id:\"minecraft:fire_resistance\",duration:-1,amplifier:0,ambient:0b,show_particles:0b,show_icon:0b}]}"
			)
		}
		pendingMobSurfaceSnap = mobX to mobZ
		mobSpawned = true
		shotCount = 0; subTick = 0
		return true
	}

	// ── Lifecycle ─────────────────────────────────────────────────────────────

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
		targetBucket = 0
		currentMobEntityType = null
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

	internal fun start(mc: Minecraft) {
		running = true; phase = Phase.SETUP; setupTick = 0
		mobIndex = 0; totalShots = 0
		safeY = mc.player?.y ?: 64.0
		DatasetCapture.autoMode = false
		resetMobState()
		savedFov = mc.options.fov().get()
		mc.options.fov().set(70)

		val wDesc = WeatherPhase.entries.joinToString(" ") { "${it.pct}%${it.label.first()}" }
		mc.player?.sendSystemMessage(Component.literal("§a[YoloGen] §fAuto-capture started §8- §7/yolostop  /yoloclear"))
		mc.player?.sendSystemMessage(Component.literal("§8  shots/mob: §f$SHOTS_PER_MOB §8| weather: §f$wDesc §8| time: §f+${TIME_PER_SHOT / 20}s/shot"))
		mc.player?.sendSystemMessage(Component.literal("§8  relocate every §f$RELOCATE_EVERY §8shots | biome pre-map §f±${BIOME_SCAN_RADIUS}blk §8step §f$BIOME_PREMAP_STEP §8| §f${MOB_TYPES.size} §8mob types"))
	}

	internal fun stop(mc: Minecraft) {
		running = false; phase = Phase.IDLE
		DatasetCapture.autoMode = true
		if (savedFov != -1) {
			mc.options.fov().set(savedFov); savedFov = -1
		}
		mc.player?.sendSystemMessage(Component.literal("§c[YoloGen] §fStopped - $mobIndex mobs, $totalShots shots captured"))
	}

	// ── Main tick ─────────────────────────────────────────────────────────────

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
					val sLevel = serverLevel(mc)
					if (sLevel != null) {
						mc.singleplayerServer!!.execute {
							sLevel.allEntities.filter { it.entityTags().contains(MOB_TAG) }.forEach { it.discard() }
							sLevel.allEntities.filter { it !is ServerPlayer }.forEach { it.discard() }
						}
					} else {
						conn.sendCommand("kill @e[type=!player]")
					}
					serverPlayer(mc)?.gameMode?.changeGameModeForPlayer(GameType.SPECTATOR)
						?: conn.sendCommand("gamemode spectator")
					baseTime = Random.nextLong(0, 24000)
					baseX = Random.nextInt(-500, 500)
					baseZ = Random.nextInt(-500, 500)
					resetMobState()
					startPoolBuild()
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
					terrainWaitTick = TERRAIN_POST_SNAP_TICKS
					return
				}

				// Wait after mob snap so chunk meshes finish building before orbit shots.
				if (terrainWaitTick > 0) {
					terrainWaitTick--
					if (terrainWaitTick > 0) return
				}

				// Re-lock rotation every tick - guards against server packet resets.
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
