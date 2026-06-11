package com.ayfri.yologen

import com.ayfri.yologen.config.ConfigHolder
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level
import java.util.*
import kotlin.math.roundToInt

internal enum class WeatherPhase(val label: String) {
	CLEAR("clear"),
	RAIN("rain"),
	THUNDER("thunder");

	fun fraction(cfg: com.ayfri.yologen.config.YoloConfig): Float = when (this) {
		CLEAR -> cfg.weatherClearFraction
		RAIN -> cfg.weatherRainFraction
		THUNDER -> (1f - cfg.weatherClearFraction - cfg.weatherRainFraction).coerceAtLeast(0f)
	}

	fun pct(cfg: com.ayfri.yologen.config.YoloConfig): Int = (fraction(cfg) * 100).roundToInt()

	companion object {
		fun forShot(idx: Int, total: Int, clearFrac: Float, rainFrac: Float): WeatherPhase {
			val clearEnd = (total * clearFrac).roundToInt()
			val rainEnd = clearEnd + (total * rainFrac).roundToInt()
			return when {
				idx < clearEnd -> CLEAR
				idx < rainEnd -> RAIN
				else -> THUNDER
			}
		}
	}
}

internal val MobDimension.levelKey: ResourceKey<Level>
	get() = when (this) {
		MobDimension.OVERWORLD -> Level.OVERWORLD
		MobDimension.NETHER -> Level.NETHER
		MobDimension.END -> Level.END
	}

internal val MobDimension.label: String
	get() = when (this) {
		MobDimension.OVERWORLD -> "OW"
		MobDimension.NETHER -> "Nether"
		MobDimension.END -> "End"
	}

internal fun Double.fmt(decimals: Int = 2) = String.format(Locale.ROOT, "%.${decimals}f", this)
internal fun Int.toChunkCoord() = floorDiv(16)

data object AutoCapture {
	internal const val MOB_TAG = "yologen_mob"
	internal const val MOB_SPAWN_TICK = 35
	internal const val PRELOAD_CHUNK_RADIUS = 2
	internal const val PRELOAD_HEIGHT = 200.0
	internal const val NETHER_PRELOAD_HEIGHT = 80.0
	internal const val RELOCATE_WAIT_TICKS = 20
	internal const val POST_SNAP_TICKS = 8
	internal const val SETUP_WAIT_TICKS = 50
	internal const val TIER_SIZE = 25

	var running = false
		private set
	internal var completedCount = 0
	internal var currentMobName = ""
	internal var currentTime = 0L
	internal var currentWeather = "clear"
	internal var mobIndex = 0
	internal var phase = Phase.IDLE
	internal var setupTick = 0
	internal var shotCount = 0
	internal var terrainWaitTick = 0
	internal var totalShots = 0
	internal var currentDimension = MobDimension.OVERWORLD
	internal var dimensionPhaseIndex = 0

	@JvmStatic
	fun isRunning(): Boolean = running

	internal var currentMobEntityType: EntityType<*>? = null

	internal var completedMobs = emptySet<String>()
	internal var baseTime = 0L
	internal var subTick = 0
	internal var completedWithoutImage = 0
	internal var currentDimensionKey: ResourceKey<Level> = Level.OVERWORLD
	internal var baseX = 0
	internal var baseZ = 0
	internal var mobX = 0.0
	internal var mobY = 64.0
	internal var mobZ = 0.0
	internal var safeY = 64.0
	internal var currentMobRegName = ""
	internal var lastRelocatedAtShot = -1
	internal var mobSpawned = false
	internal var nextSetupBaseX: Int? = null
	internal var nextSetupBaseZ: Int? = null
	internal var nextRelocation: Pair<Double, Double>? = null
	internal var pendingMobSurfaceSnap: Pair<Double, Double>? = null
	internal var relocationCursor = 0
	@Volatile internal var relocationPool = emptyList<BiomeRelocation>()
	internal val cachedPools = mutableMapOf<MobDimension, List<BiomeRelocation>>()
	@Volatile internal var poolBuildDone = false
	internal var targetPitch = 0f
	internal var targetYaw = 0f

	internal var startTimeMs = 0L

	private var savedFov = -1
	private var savedRenderDistance = -1
	private var savedSimDistance = -1
	private var savedEntityShadows = true

	internal val hasNextRelocation get() = nextRelocation != null
	internal val relocationPoolSize get() = relocationPool.size
	internal val currentMobIsAquatic get() = mobIndex < MOB_ENTRIES.size && MOB_ENTRIES[mobIndex].aquatic

	internal enum class Phase { IDLE, SETUP, CAPTURING }

	internal val basePosRange: IntRange get() = if (currentDimension == MobDimension.END) -150..150 else -500..500

	internal val SHOTS_PER_MOB get() = if (DatasetCapture.debugBBMode) 1 else ConfigHolder.config.shotsPerMob
	internal val RELOCATE_EVERY get() = ConfigHolder.config.relocateEvery
	internal val BIOME_SCAN_RADIUS get() = ConfigHolder.config.biomeSearchRadius
	internal val TERRAIN_WAIT_TICKS get() = RELOCATE_WAIT_TICKS

	internal fun mobRegName(idx: Int) =
		BuiltInRegistries.ENTITY_TYPE.getKey(MOB_ENTRIES[idx].entityType).toString()

	internal fun findNextMob(done: Set<String>) = MOB_ENTRIES.indexOfFirst {
		BuiltInRegistries.ENTITY_TYPE.getKey(it.entityType).toString().substringAfter(':') !in done
	}

	internal fun weatherForShot(idx: Int): String {
		val cfg = ConfigHolder.config
		return WeatherPhase.forShot(idx, cfg.shotsPerMob, cfg.weatherClearFraction, cfg.weatherRainFraction).label
	}

	internal fun shotLimitForPhase(phaseIdx: Int, numDims: Int): Int {
		val cfg = ConfigHolder.config
		val base = cfg.shotsPerMob / numDims
		return if (phaseIdx == numDims - 1) cfg.shotsPerMob else base * (phaseIdx + 1)
	}

	internal fun resetShotState() {
		shotCount = 0
		subTick = 0
		terrainWaitTick = 0
		lastRelocatedAtShot = -1
		mobSpawned = false
		nextRelocation = null
		pendingMobSurfaceSnap = null
		relocationCursor = 0
		currentMobEntityType = null
		completedWithoutImage = 0
		dimensionPhaseIndex = 0
	}

	internal fun resetMobState() {
		resetShotState()
		poolBuildDone = false
		relocationPool = emptyList()
	}

	internal fun resetDimPhaseState() {
		subTick = 0
		terrainWaitTick = 0
		lastRelocatedAtShot = -1
		mobSpawned = false
		nextRelocation = null
		pendingMobSurfaceSnap = null
		relocationCursor = 0
		completedWithoutImage = 0
		val cached = cachedPools[currentDimension]
		if (cached != null) {
			relocationPool = cached
			poolBuildDone = true
		} else {
			poolBuildDone = false
			relocationPool = emptyList()
		}
	}

	fun register() {
		ClientTickEvents.END_CLIENT_TICK.register { mc ->
			if (running) tick(mc)
		}
	}

	internal fun start(mc: Minecraft) {
		val cfg = ConfigHolder.config
		completedMobs = ProgressStore.load(mc)
		val nextIdx = findNextMob(completedMobs)
		if (nextIdx == -1) {
			mc.player?.sendSystemMessage(Component.literal("§a[YoloGen] §fAll ${MOB_ENTRIES.size} mobs already captured. Use §7/yoloclear §fto reset."))
			return
		}
		mobIndex = nextIdx
		completedCount = completedMobs.size
		running = true
		phase = Phase.SETUP
		setupTick = 0
		startTimeMs = System.currentTimeMillis()
		totalShots = completedMobs.size * cfg.shotsPerMob
		safeY = mc.player?.y ?: 64.0
		DatasetCapture.autoMode = false
		DatasetCapture.resumeFrom(ProgressStore.resumeCaptureIndex(mc))
		resetMobState()
		if (completedMobs.isEmpty()) cachedPools.clear()

		savedFov = mc.options.fov().get()
		mc.options.fov().set(70)
		savedRenderDistance = mc.options.renderDistance().get()
		mc.options.renderDistance().set(cfg.captureRenderDistance)
		savedSimDistance = mc.options.simulationDistance().get()
		mc.options.simulationDistance().set(cfg.captureRenderDistance)
		savedEntityShadows = mc.options.entityShadows().get()
		mc.options.entityShadows().set(false)

		val wDesc = "60%☀ 20%☂ 20%⚡"
		val resumeMsg = if (completedMobs.isNotEmpty())
			" §8(resuming from §f${mobRegName(nextIdx).substringAfter(':')}§8, ${completedMobs.size}/${MOB_ENTRIES.size} done)"
		else ""
		mc.player?.sendSystemMessage(Component.literal("§a[YoloGen] §fAuto-capture started$resumeMsg §8- §7/yolostop  /yoloreload"))
		mc.player?.sendSystemMessage(Component.literal("§8  shots/mob: §f${cfg.shotsPerMob} §8| weather: §f$wDesc §8| render dist: §f${cfg.captureRenderDistance}"))
		mc.player?.sendSystemMessage(Component.literal("§8  relocate every §f${cfg.relocateEvery} §8shots | biome radius §f±${cfg.biomeSearchRadius}blk §8| §f${MOB_ENTRIES.size} §8mob types"))

		setGameRulesDirect(mc, spawnMobs = false, advTime = false, advWeather = false, randomTick = 0)
	}

	internal fun onClear() {
		completedMobs = emptySet()
		completedCount = 0
		cachedPools.clear()
	}

	internal fun stop(mc: Minecraft) {
		running = false; phase = Phase.IDLE
		DatasetCapture.autoMode = true
		DatasetCapture.debugBBMode = false

		if (savedFov != -1) { mc.options.fov().set(savedFov); savedFov = -1 }
		if (savedRenderDistance != -1) { mc.options.renderDistance().set(savedRenderDistance); savedRenderDistance = -1 }
		if (savedSimDistance != -1) { mc.options.simulationDistance().set(savedSimDistance); savedSimDistance = -1 }
		mc.options.entityShadows().set(savedEntityShadows)

		mc.player?.sendSystemMessage(Component.literal("§c[YoloGen] §fStopped - $mobIndex mobs, $totalShots shots captured"))
		setGameRulesDirect(mc, spawnMobs = true, advTime = true, advWeather = true, randomTick = 3)
	}

	private fun tick(mc: Minecraft) {
		val cfg = ConfigHolder.config

		if (phase == Phase.CAPTURING) {
			val dimLabel = if (currentDimension != MobDimension.OVERWORLD) " §8[§7${currentDimension.label}§8]" else ""
			val icon = when (currentWeather) { "rain" -> "§9☂"; "thunder" -> "§5⚡"; else -> "§a☀" }
			val hour = (currentTime / 1000L + 6L) % 24L
			mc.gui.setOverlayMessage(
				Component.literal(
					"$icon §f$currentMobName$dimLabel §8| §7shot ${shotCount + 1}/${cfg.shotsPerMob} §8| §7mob ${completedCount + 1}/${MOB_ENTRIES.size} §8| §7%02d:00".format(hour)
				), false
			)
		}

		when (phase) {
			Phase.IDLE -> {}
			Phase.SETUP -> tickSetup(mc)
			Phase.CAPTURING -> tickCapturing(mc)
		}
	}
}
