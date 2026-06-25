package com.ayfri.yologen

import com.ayfri.yologen.AutoCapture.poolBuildDone
import com.ayfri.yologen.AutoCapture.relocationPool
import com.ayfri.yologen.config.ConfigHolder
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.FluidTags
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.*
import net.minecraft.world.entity.animal.axolotl.Axolotl
import net.minecraft.world.entity.animal.chicken.Chicken
import net.minecraft.world.entity.animal.chicken.ChickenVariants
import net.minecraft.world.entity.animal.cow.Cow
import net.minecraft.world.entity.animal.cow.CowVariants
import net.minecraft.world.entity.animal.cow.MushroomCow
import net.minecraft.world.entity.animal.equine.Horse
import net.minecraft.world.entity.animal.equine.Llama
import net.minecraft.world.entity.animal.equine.Markings
import net.minecraft.world.entity.animal.feline.Cat
import net.minecraft.world.entity.animal.feline.CatVariants
import net.minecraft.world.entity.animal.fish.Pufferfish
import net.minecraft.world.entity.animal.fish.Salmon
import net.minecraft.world.entity.animal.fish.TropicalFish
import net.minecraft.world.entity.animal.fox.Fox
import net.minecraft.world.entity.animal.frog.Frog
import net.minecraft.world.entity.animal.frog.FrogVariants
import net.minecraft.world.entity.animal.goat.Goat
import net.minecraft.world.entity.animal.panda.Panda
import net.minecraft.world.entity.animal.parrot.Parrot
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.entity.animal.pig.PigVariants
import net.minecraft.world.entity.animal.rabbit.Rabbit
import net.minecraft.world.entity.animal.sheep.Sheep
import net.minecraft.world.entity.animal.wolf.Wolf
import net.minecraft.world.entity.animal.wolf.WolfVariants
import net.minecraft.world.entity.monster.Shulker
import net.minecraft.world.entity.monster.Slime
import net.minecraft.world.entity.monster.zombie.ZombieVillager
import net.minecraft.world.entity.npc.villager.VillagerType
import net.minecraft.world.entity.variant.VariantUtils
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.levelgen.Heightmap
import java.util.Optional
import kotlin.math.*
import kotlin.random.Random
import net.minecraft.world.entity.animal.equine.Variant as HorseVariant

internal data class BiomeRelocation(
	val biome: ResourceKey<Biome>,
	val x: Double,
	val z: Double,
	val tempBucket: Int,
)

/** Ring of horizontal offsets sampled around a spawn spot to test for an open clearing. */
private val OPENNESS_OFFSETS = listOf(
	4 to 0, -4 to 0, 0 to 4, 0 to -4, 3 to 3, -3 to 3, 3 to -3, -3 to -3
)

private fun tempBucket(temp: Float) = when {
	temp < 0.0f -> 0
	temp < 0.3f -> 1
	temp < 0.5f -> 2
	temp < 0.8f -> 3
	temp < 1.2f -> 4
	else -> 5
}

/**
 * Prepares the relocation pool build for the current dimension. Cached results are reused
 * instantly; otherwise the actual biome scan is performed incrementally by [tickBuildPool]
 * (a few biomes per server tick) so the integrated server never freezes for seconds at a time.
 */
internal fun AutoCapture.buildPoolForCurrentDimension(mc: Minecraft) {
	val cached = cachedPools[currentDimension]
	if (cached != null) {
		relocationPool = cached
		poolBuildDone = true
		return
	}
	poolBuildDone = false
	poolBuildInitialized = false
	biomeQueue = emptyList()
	biomeQueuePos = 0
	poolAccumulator.clear()
	relocationPool = emptyList()
}

/**
 * Processes a small batch of biomes per call on the server thread, accumulating relocation
 * targets via [ServerLevel.findClosestBiome3d] (samples the noise biome source without loading
 * chunks). Exposes partial progress through [relocationPool] and flips [poolBuildDone] when done.
 */
internal fun AutoCapture.tickBuildPool(mc: Minecraft) {
	if (poolBuildDone) return
	val server = mc.singleplayerServer ?: run { poolBuildDone = true; return }
	val radius = ConfigHolder.config.biomeSearchRadius

	server.execute {
		val sLevel = server.getLevel(currentDimensionKey) ?: run { poolBuildDone = true; return@execute }
		if (!poolBuildInitialized) {
			val registry = sLevel.registryAccess().lookupOrThrow(Registries.BIOME)
			biomeQueue = registry.listElements().collect(java.util.stream.Collectors.toList())
			biomeQueuePos = 0
			poolAccumulator.clear()
			poolBuildInitialized = true
		}

		val origin = BlockPos(baseX, 64, baseZ)
		var processed = 0
		while (biomeQueuePos < biomeQueue.size && processed < AutoCapture.POOL_BATCH) {
			val biomeHolder = biomeQueue[biomeQueuePos++]
			processed++
			val biomeKey = biomeHolder.unwrapKey().orElse(null) ?: continue
			try {
				val result = sLevel.findClosestBiome3d({ it == biomeHolder }, origin, radius, 48, 32)
				if (result != null) {
					val pos = result.first
					poolAccumulator.add(
						BiomeRelocation(
							biomeKey,
							pos.x.toDouble(),
							pos.z.toDouble(),
							tempBucket(biomeHolder.value().baseTemperature)
						)
					)
				}
			} catch (_: Exception) {
				// biome not found within radius - skip
			}
		}
		// Expose partial progress so relocations can begin and the HUD shows the count growing.
		relocationPool = poolAccumulator.toList()

		if (biomeQueuePos >= biomeQueue.size) {
			relocationPool = poolAccumulator.shuffled()
			cachedPools[currentDimension] = relocationPool
			poolBuildDone = true
		}
	}
}

internal fun AutoCapture.fallbackRelocation(): Pair<Double, Double> {
	val range = basePosRange
	return Random.nextInt(range.first, range.last + 1).toDouble() to
		Random.nextInt(range.first, range.last + 1).toDouble()
}

internal fun AutoCapture.chooseRelocation(): Pair<Double, Double> {
	if (relocationPool.isNotEmpty()) {
		val pick = relocationPool[relocationCursor % relocationPool.size]
		relocationCursor = (relocationCursor + 1) % relocationPool.size
		return pick.x to pick.z
	}
	return fallbackRelocation()
}

internal fun AutoCapture.netherFloorYServer(sLevel: ServerLevel, x: Int, z: Int): Double {
	for (y in 110 downTo 5) {
		val state = sLevel.getBlockState(BlockPos(x, y, z))
		if (!state.isAir && sLevel.getBlockState(BlockPos(x, y + 1, z)).isAir) {
			val clearOk = (1..3).all { dy -> sLevel.getBlockState(BlockPos(x, y + dy, z)).isAir }
			if (clearOk) return (y + 1).toDouble()
		}
	}
	return 40.0
}

internal fun AutoCapture.loadedSurfaceYServer(sLevel: ServerLevel, x: Int, z: Int): Double? {
	if (!sLevel.isLoaded(BlockPos(x, 0, z))) return null
	return if (currentDimension == MobDimension.NETHER) netherFloorYServer(sLevel, x, z)
	else sLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z).toDouble()
}

/**
 * Finds a standable Nether floor at (x, z): the highest non-lava solid block whose footing has
 * enough head-room for the current mob. Scans from just under the bedrock ceiling down to the
 * lava sea, rejecting fluid (lava) floors and ceilings without clearance.
 *
 * Returns the Y to stand on, or null if the whole column is lava / solid / too cramped - callers
 * picking a spawn point should then try another (x, z) rather than dropping the mob into lava.
 */
internal fun AutoCapture.netherSpawnFloorY(mc: Minecraft, x: Int, z: Int): Double? {
	val level = mc.level ?: return null
	val mobHeight = currentMobEntityType?.height ?: 1.8f
	val neededClear = (ceil(mobHeight).toInt() + 1).coerceIn(3, 6)
	var y = 118
	while (y >= 8) {
		val floorPos = BlockPos(x, y, z)
		val floorState = level.getBlockState(floorPos)
		val solidFloor = !floorState.isAir &&
			level.getFluidState(floorPos).isEmpty &&
			!floorState.getCollisionShape(level, floorPos).isEmpty
		if (solidFloor) {
			val headroom = (1..neededClear).all { dy ->
				val p = BlockPos(x, y + dy, z)
				level.getBlockState(p).isAir && level.getFluidState(p).isEmpty
			}
			if (headroom) return (y + 1).toDouble()
		}
		y--
	}
	return null
}

/** Non-null Nether floor for camera/base positioning: prefers a real open floor, else a safe default. */
private fun AutoCapture.netherFloorY(mc: Minecraft, x: Int, z: Int): Double =
	netherSpawnFloorY(mc, x, z) ?: 40.0

/**
 * Finds a clear spawn position with sufficient vertical and horizontal clearance.
 * Prevents mobs from spawning inside walls or under overhangs.
 */
internal fun AutoCapture.findClearPos(
	mc: Minecraft,
	centerX: Double,
	centerZ: Double,
	radius: Int = 30
): Pair<Double, Double> {
	val level = mc.level ?: return centerX to centerZ
	val mobHeight = (currentMobEntityType?.height ?: 1.8f)
	val neededClear = ceil(mobHeight).toInt() + 1
	val hw = (currentMobEntityType?.width ?: 0.6f) / 2f + 0.1f

	// The Nether is a cramped 3D cave system riddled with lava, so valid footing is rarer than on
	// the overworld surface - give it many more attempts before falling back to the centre.
	val isNether = currentDimension == MobDimension.NETHER
	val attempts = if (isNether) 96 else 40

	for (i in 0..<attempts) {
		val x = centerX + Random.nextInt(-radius, radius + 1)
		val z = centerZ + Random.nextInt(-radius, radius + 1)
		val bp = BlockPos(x.toInt(), 0, z.toInt())
		if (!level.isLoaded(bp)) continue

		val y: Double
		if (isNether) {
			// netherSpawnFloorY already guarantees a non-lava floor with mob head-room.
			y = netherSpawnFloorY(mc, x.toInt(), z.toInt()) ?: continue
		} else {
			val blocking = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x.toInt(), z.toInt())
			val noLeaves = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x.toInt(), z.toInt())
			if (blocking > noLeaves + 1) continue
			// Prefer open clearings: most nearby columns should sit near the mob's ground
			// height. Tall trees or cliffs around the mob occlude the orbiting camera and
			// cause skipped shots. Relaxed after 25 tries so we never fail to place a mob.
			if (i < 25) {
				val openCount = OPENNESS_OFFSETS.count { (ox, oz) ->
					val sx = x.toInt() + ox
					val sz = z.toInt() + oz
					level.isLoaded(BlockPos(sx, 0, sz)) &&
						level.getHeight(Heightmap.Types.MOTION_BLOCKING, sx, sz) <= noLeaves + 2
				}
				if (openCount < 6) continue
			}
			y = noLeaves.toDouble()
			val verticalClear = (0..<neededClear).all { dy ->
				level.getBlockState(BlockPos(x.toInt(), y.toInt() + dy, z.toInt())).isAir
			}
			if (!verticalClear) continue
		}

		val horizontalClear = listOf(
			BlockPos(x.toInt() + hw.roundToInt() + 1, y.toInt() + 1, z.toInt()),
			BlockPos(x.toInt() - hw.roundToInt() - 1, y.toInt() + 1, z.toInt()),
			BlockPos(x.toInt(), y.toInt() + 1, z.toInt() + hw.roundToInt() + 1),
			BlockPos(x.toInt(), y.toInt() + 1, z.toInt() - hw.roundToInt() - 1),
		).all { checkPos -> level.getBlockState(checkPos).getCollisionShape(level, checkPos).isEmpty }
		if (!horizontalClear) continue

		return x to z
	}
	return centerX to centerZ
}

internal fun AutoCapture.loadedSurfaceY(mc: Minecraft, x: Int, z: Int): Double? {
	val level = mc.level ?: return null
	if (!level.isLoaded(BlockPos(x, 0, z))) return null
	return if (currentDimension == MobDimension.NETHER) netherFloorY(mc, x, z)
	else level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z).toDouble()
}

internal fun AutoCapture.safeSurfaceY(mc: Minecraft, x: Int, z: Int): Double {
	val level = mc.level ?: return safeY
	if (!level.isLoaded(BlockPos(x, 0, z))) return safeY
	return if (currentDimension == MobDimension.NETHER) {
		netherFloorY(mc, x, z).also { if (it < 5.0) return safeY }
	} else {
		val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z).toDouble()
		if (y < 60.0) safeY else y
	}
}

internal fun AutoCapture.spawnSingleMob(
	sLevel: ServerLevel,
	mc: Minecraft,
	x: Double, y: Double, z: Double,
	entityType: EntityType<*>,
	cfg: com.ayfri.yologen.config.YoloConfig,
) {
	val entity = entityType.create(sLevel, EntitySpawnReason.COMMAND) ?: return
	entity.snapTo(x, y, z, Random.nextFloat() * 360f, 0f)

	// Populate natural equipment + variants (skeleton bow, pillager crossbow, drowned trident,
	// piglin gold sword, vindicator/illusioner weapons, ...). EntityType.create alone never does this.
	// Must run before disabling AI. EVENT reason avoids spider/zombie jockey group spawns.
	if (entity is Mob) {
		runCatching {
			entity.finalizeSpawn(
				sLevel,
				sLevel.getCurrentDifficultyAt(BlockPos(x.toInt(), y.toInt(), z.toInt())),
				EntitySpawnReason.EVENT,
				null,
			)
		}
		// Strip any jockey vehicle finalizeSpawn may still attach (e.g. baby-zombie chicken jockey).
		if (entity.isPassenger) {
			val vehicle = entity.vehicle
			entity.stopRiding()
			vehicle?.discard()
		}
	}

	entity.isInvulnerable = true
	entity.clearFire()
	(entity as? Mob)?.isNoAi = true
	(entity as? LivingEntity)?.addEffect(
		MobEffectInstance(MobEffects.FIRE_RESISTANCE, -1, 0, false, false, false)
	)
	entity.addTag(MOB_TAG)
	entity.setGlowingTag(true)

	if (cfg.babies && Random.nextBoolean()) (entity as? AgeableMob)?.isBaby = true

	// finalizeSpawn() above already rolled a biome/random appearance for many mobs. When variants
	// are on we override it with our own random roll; when off we force the canonical default so
	// the mob never silently inherits a biome look from the relocation position.
	if (cfg.variants) applyRandomVariant(entity)
	else resetSpawnVariantToDefault(entity)

	if (cfg.equipmentAndPoses && entity is Mob) {
		val armorItems = listOf(
			Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
			Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.GOLDEN_HELMET, Items.LEATHER_CHESTPLATE
		)
		val weaponItems = listOf(Items.IRON_SWORD, Items.BOW, Items.CROSSBOW, Items.TRIDENT, null)
		val slots = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
		slots.forEach { slot ->
			if (Random.nextBoolean()) {
				entity.setItemSlot(slot, ItemStack(armorItems.random()))
				entity.setDropChance(slot, 0f)
			}
		}
		if (Random.nextFloat() < 0.3f) {
			weaponItems.random()?.let { entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(it)) }
		}
	}

	sLevel.addFreshEntity(entity)
}

/** Shared RNG for registry-backed variant rolls (net.minecraft RandomSource, not kotlin.random). */
private val VARIANT_RNG = RandomSource.create()

/** The 7 biome villager skins (zombie villager inherits them). */
private val VILLAGER_TYPES = listOf(
	VillagerType.PLAINS, VillagerType.DESERT, VillagerType.JUNGLE,
	VillagerType.SAVANNA, VillagerType.SNOW, VillagerType.SWAMP, VillagerType.TAIGA,
)

/**
 * Rolls a random appearance for every mob that has more than one look. Runs only when `variants`
 * is enabled. Each branch overrides whatever [Mob.finalizeSpawn] already picked. See VARIANTS.md
 * for the full per-mob table.
 */
private fun applyRandomVariant(entity: Entity) {
	when (entity) {
		// Dye-color appearance.
		is Sheep -> entity.color = DyeColor.entries.random()
		is Shulker -> setVariantReflect(entity, Optional.of(DyeColor.entries.random()))
		// Enum / data variants (setVariant is private on most of these -> reflection).
		is Horse -> setVariantAndMarkingsReflect(entity, HorseVariant.entries.random(), Markings.entries.random())
		is Llama -> setVariantReflect(entity, Llama.Variant.entries.random()) // also covers TraderLlama
		is Rabbit -> setVariantReflect(entity, Rabbit.Variant.entries.filter { it != Rabbit.Variant.EVIL }.random())
		is Parrot -> setVariantReflect(entity, Parrot.Variant.entries.random())
		is Axolotl -> setVariantReflect(entity, Axolotl.Variant.entries.random())
		is Fox -> setVariantReflect(entity, Fox.Variant.entries.random())
		is Salmon -> setVariantReflect(entity, Salmon.Variant.entries.random()) // small/medium/large = size
		is TropicalFish -> setPackedVariantReflect(entity, TropicalFish.COMMON_VARIANTS.random().packedId)
		is MushroomCow -> setVariantReflect(
			entity,
			MushroomCow.Variant.entries.filter { it != MushroomCow.Variant.DEFAULT }.random()
		)
		// Size-driven looks (changes the bounding box, so the YOLO box follows).
		is Pufferfish -> entity.setPuffState(Random.nextInt(0, 3)) // 0=small, 1=mid, 2=full
		is Slime -> entity.setSize(Random.nextInt(1, 5), true) // also covers MagmaCube; 1..4
		// Panda gene pair: set both genes to the same value so even recessive looks are expressed.
		is Panda -> Panda.Gene.entries.random().let { entity.setMainGene(it); entity.setHiddenGene(it) }
		// Boolean trait.
		is Goat -> entity.isScreamingGoat = Random.nextBoolean()
		// Registry biome variants (warm/cold/temperate, cat breeds, wolf coats, frog colors).
		is Cow -> rollRegistryVariant(entity, Registries.COW_VARIANT)
		is Chicken -> rollRegistryVariant(entity, Registries.CHICKEN_VARIANT)
		is Pig -> rollRegistryVariant(entity, Registries.PIG_VARIANT)
		is Cat -> rollRegistryVariant(entity, Registries.CAT_VARIANT)
		is Wolf -> rollRegistryVariant(entity, Registries.WOLF_VARIANT)
		is Frog -> rollRegistryVariant(entity, Registries.FROG_VARIANT)
		// Villager biome skin + profession (zombie villager only; plain villager is not captured).
		is ZombieVillager -> randomizeVillager(entity)
	}
}

/**
 * Forces the canonical default appearance for every mob whose [Mob.finalizeSpawn] randomizes its
 * look. Runs when `variants` is disabled so the dataset is reproducible and a mob never silently
 * inherits a biome look from the relocation position. See VARIANTS.md for the per-mob defaults.
 */
private fun resetSpawnVariantToDefault(entity: Entity) {
	when (entity) {
		is Sheep -> entity.color = DyeColor.WHITE
		is Horse -> setVariantAndMarkingsReflect(entity, HorseVariant.WHITE, Markings.NONE)
		is Llama -> setVariantReflect(entity, Llama.Variant.DEFAULT)
		is Rabbit -> setVariantReflect(entity, Rabbit.Variant.DEFAULT)
		is Parrot -> setVariantReflect(entity, Parrot.Variant.DEFAULT)
		is Axolotl -> setVariantReflect(entity, Axolotl.Variant.DEFAULT)
		is Fox -> setVariantReflect(entity, Fox.Variant.DEFAULT)
		is Salmon -> setVariantReflect(entity, Salmon.Variant.DEFAULT)
		is TropicalFish -> setPackedVariantReflect(entity, TropicalFish.DEFAULT_VARIANT.packedId)
		is MushroomCow -> setVariantReflect(entity, MushroomCow.Variant.DEFAULT)
		is Slime -> entity.setSize(2, true)
		is Panda -> { entity.setMainGene(Panda.Gene.NORMAL); entity.setHiddenGene(Panda.Gene.NORMAL) }
		is Goat -> entity.isScreamingGoat = false
		is Cow -> resetRegistryVariant(entity, CowVariants.DEFAULT)
		is Chicken -> resetRegistryVariant(entity, ChickenVariants.DEFAULT)
		is Pig -> resetRegistryVariant(entity, PigVariants.DEFAULT)
		is Cat -> resetRegistryVariant(entity, CatVariants.BLACK)
		is Wolf -> resetRegistryVariant(entity, WolfVariants.DEFAULT)
		is Frog -> resetRegistryVariant(entity, FrogVariants.TEMPERATE)
		is ZombieVillager -> entity.villagerData = entity.villagerData.withType(entity.registryAccess(), VillagerType.PLAINS)
	}
}

/** Rolls a random registry-backed variant (cat/wolf/frog/cow/pig/chicken). */
private fun <T : Any> rollRegistryVariant(entity: Entity, registryKey: ResourceKey<out Registry<T>>) {
	entity.registryAccess().lookupOrThrow(registryKey).getRandom(VARIANT_RNG).ifPresent { setVariantReflect(entity, it) }
}

/** Resets a registry-backed variant to its declared default. */
private fun <T : Any> resetRegistryVariant(entity: Entity, defaultKey: ResourceKey<T>) {
	setVariantReflect(entity, VariantUtils.getDefaultOrAny(entity.registryAccess(), defaultKey))
}

/** Randomizes a zombie villager's biome skin and profession (both change its texture). */
private fun randomizeVillager(zv: ZombieVillager) {
	val registries = zv.registryAccess()
	var data = zv.villagerData.withType(registries, VILLAGER_TYPES.random())
	registries.lookupOrThrow(Registries.VILLAGER_PROFESSION).getRandom(VARIANT_RNG)
		.ifPresent { data = data.withProfession(it) }
	zv.villagerData = data
}

/** Sets a private/public single-arg `setVariant(x)`, walking the class hierarchy (e.g. TraderLlama). */
private fun setVariantReflect(entity: Any, variant: Any) {
	runCatching {
		var cls: Class<*>? = entity.javaClass
		while (cls != null) {
			val method = cls.declaredMethods.firstOrNull { it.name == "setVariant" && it.parameterCount == 1 }
			if (method != null) {
				method.isAccessible = true
				method.invoke(entity, variant)
				return
			}
			cls = cls.superclass
		}
	}
}

/** Sets a private `setVariantAndMarkings(variant, markings)` (horse coat + markings). */
private fun setVariantAndMarkingsReflect(entity: Any, variant: Any, markings: Any) {
	runCatching {
		var cls: Class<*>? = entity.javaClass
		while (cls != null) {
			val method = cls.declaredMethods.firstOrNull { it.name == "setVariantAndMarkings" && it.parameterCount == 2 }
			if (method != null) {
				method.isAccessible = true
				method.invoke(entity, variant, markings)
				return
			}
			cls = cls.superclass
		}
	}
}

private fun setPackedVariantReflect(entity: TropicalFish, packedId: Int) {
	runCatching {
		val method = entity.javaClass.getDeclaredMethod("setPackedVariant", Int::class.javaPrimitiveType)
		method.isAccessible = true
		method.invoke(entity, packedId)
	}
}

/**
 * Scans for a water block within [radius] of (centerX, centerZ) and returns
 * a spawn Triple(x, y, z) positioned inside the water column.
 * Returns null if no loaded water found (caller should retry next tick).
 */
internal fun AutoCapture.findWaterPos(
	mc: Minecraft,
	centerX: Double,
	centerZ: Double,
	radius: Int = 24
): Triple<Double, Double, Double>? {
	val level = mc.level ?: return null
	val mobHeight = (currentMobEntityType?.height ?: 1.8f)
	// Water blocks needed so the mob is fully submerged with a little clearance above the floor.
	val needed = ceil(mobHeight).toInt() + 1
	repeat(40) {
		val x = centerX + Random.nextDouble(-radius.toDouble(), radius.toDouble())
		val z = centerZ + Random.nextDouble(-radius.toDouble(), radius.toDouble())
		if (!level.isLoaded(BlockPos(x.toInt(), 0, z.toInt()))) return@repeat
		val surfY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x.toInt(), z.toInt())

		// Topmost water block.
		var topWater = -1
		for (y in surfY downTo maxOf(surfY - 30, 20)) {
			if (level.getFluidState(BlockPos(x.toInt(), y, z.toInt())).`is`(FluidTags.WATER)) {
				topWater = y; break
			}
		}
		if (topWater < 0) return@repeat

		// Scan down to the solid floor (first non-water block).
		var floor = topWater
		while (floor > topWater - 30 &&
			level.getFluidState(BlockPos(x.toInt(), floor, z.toInt())).`is`(FluidTags.WATER)
		) floor--

		// Reject shallow water that would sink a big mob into the floor.
		val waterDepth = topWater - floor
		if (waterDepth < needed) return@repeat

		// Feet just above the floor; allow some vertical variation while keeping the
		// whole body inside the water column.
		val minFeet = floor + 1
		val maxFeet = topWater - ceil(mobHeight).toInt()
		val feetY = if (maxFeet > minFeet) Random.nextInt(minFeet, maxFeet + 1) else minFeet
		return Triple(x, feetY.toDouble(), z)
	}
	return null
}

/** Solid floor Y at (x,z): first non-water, non-air block scanning down from the surface. */
internal fun AutoCapture.waterFloorY(mc: Minecraft, x: Int, z: Int): Double {
	val level = mc.level ?: return mobY - 6.0
	if (!level.isLoaded(BlockPos(x, 0, z))) return mobY - 6.0
	val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
	val bottom = maxOf(top - 40, -64)
	for (y in top downTo bottom) {
		val pos = BlockPos(x, y, z)
		if (!level.getFluidState(pos).`is`(FluidTags.WATER) && !level.getBlockState(pos).isAir) {
			return (y + 1).toDouble()
		}
	}
	return bottom.toDouble()
}

internal fun AutoCapture.spawnMobEntity(mc: Minecraft, x: Double, y: Double, z: Double) {
	val server = mc.singleplayerServer ?: return
	val cfg = ConfigHolder.config
	server.execute {
		val sLevel = server.getLevel(currentDimensionKey) ?: return@execute
		sLevel.allEntities.filter { it !is ServerPlayer }.forEach { it.discard() }

		spawnSingleMob(sLevel, mc, x, y, z, currentMobEntityType ?: return@execute, cfg)

		if (cfg.multipleMobsPerFrame && cfg.extraMobsCount > 1) {
			val extra = Random.nextInt(0, cfg.extraMobsCount)
			val isAquatic = currentMobIsAquatic
			repeat(extra) {
				val angle = Random.nextDouble(0.0, 2.0 * PI)
				val dist = Random.nextDouble(2.0, 6.0)
				val ex = x + cos(angle) * dist
				val ez = z + sin(angle) * dist
				val ey = if (isAquatic) y + Random.nextDouble(-2.0, 2.0)
				else loadedSurfaceYServer(sLevel, ex.toInt(), ez.toInt()) ?: y
				spawnSingleMob(sLevel, mc, ex, ey, ez, currentMobEntityType ?: return@execute, cfg)
			}
		}
	}
}

internal fun AutoCapture.spawnMobOnLoadedSurface(mc: Minecraft): Boolean {
	val entry = MOB_ENTRIES[mobIndex]
	currentMobEntityType = entry.entityType
	currentMobRegName = BuiltInRegistries.ENTITY_TYPE.getKey(entry.entityType).toString()
	currentMobName = currentMobRegName.substringAfter(':')

	val surfY = loadedSurfaceY(mc, baseX, baseZ) ?: return false
	safeY = surfY
	if (entry.aquatic) {
		val waterPos = findWaterPos(mc, baseX.toDouble(), baseZ.toDouble())
		if (waterPos != null) {
			val (wx, wy, wz) = waterPos
			safeY = wy
			teleportPlayerToDimension(mc, wx, wy, wz)
			mobX = wx; mobY = wy; mobZ = wz
		} else {
			teleportPlayerToDimension(mc, baseX.toDouble(), surfY, baseZ.toDouble())
			val (clearMobX, clearMobZ) = findClearPos(mc, baseX.toDouble(), baseZ.toDouble())
			mobX = clearMobX; mobZ = clearMobZ
			mobY = loadedSurfaceY(mc, mobX.toInt(), mobZ.toInt()) ?: surfY
		}
	} else {
		teleportPlayerToDimension(mc, baseX.toDouble(), surfY, baseZ.toDouble())
		val (clearMobX, clearMobZ) = findClearPos(mc, baseX.toDouble(), baseZ.toDouble())
		mobX = clearMobX; mobZ = clearMobZ
		mobY = loadedSurfaceY(mc, mobX.toInt(), mobZ.toInt()) ?: surfY
	}

	if (mc.singleplayerServer != null) {
		spawnMobEntity(mc, mobX, mobY, mobZ)
	} else {
		mc.player?.connection?.sendCommand(
			"summon $currentMobRegName ${mobX.fmt()} ${mobY.fmt()} ${mobZ.fmt()} " +
				"{Invulnerable:1b,NoAI:1b,Tags:[\"$MOB_TAG\"],Glowing:1b," +
				"active_effects:[{id:\"minecraft:fire_resistance\",duration:-1,amplifier:0,ambient:0b,show_particles:0b,show_icon:0b}]}"
		)
	}
	pendingMobSurfaceSnap = mobX to mobZ
	mobSpawned = true
	return true
}

internal fun AutoCapture.snapMobToLoadedSurface(mc: Minecraft, x: Double, z: Double): Boolean {
	val level = mc.level ?: return false
	forceServerChunksAround(mc, x.toInt(), z.toInt(), radius = 1)
	if (!level.isLoaded(BlockPos(x.toInt(), 0, z.toInt()))) return false

	val (finalX, finalY, finalZ) = if (currentMobIsAquatic) {
		findWaterPos(mc, x, z) ?: run {
			val (clearX, clearZ) = findClearPos(mc, x, z)
			val y = loadedSurfaceY(mc, clearX.toInt(), clearZ.toInt()) ?: return false
			Triple(clearX, y, clearZ)
		}
	} else {
		val (clearX, clearZ) = findClearPos(mc, x, z)
		val y = loadedSurfaceY(mc, clearX.toInt(), clearZ.toInt()) ?: return false
		Triple(clearX, y, clearZ)
	}

	if (mc.singleplayerServer != null) {
		spawnMobEntity(mc, finalX, finalY, finalZ)
	} else {
		val conn = mc.player?.connection ?: return false
		conn.sendCommand("kill @e[type=!player]")
		conn.sendCommand(
			"summon $currentMobRegName ${finalX.fmt()} ${finalY.fmt()} ${finalZ.fmt()} " +
				"{Invulnerable:1b,NoAI:1b,Tags:[\"$MOB_TAG\"],Glowing:1b," +
				"active_effects:[{id:\"minecraft:fire_resistance\",duration:-1,amplifier:0,ambient:0b,show_particles:0b,show_icon:0b}]}"
		)
	}
	mobX = finalX; mobY = finalY; mobZ = finalZ
	nextRelocation = null
	pendingMobSurfaceSnap = null
	safeY = finalY
	return true
}
