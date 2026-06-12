package com.ayfri.yologen

import com.ayfri.yologen.config.ConfigHolder
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.FluidTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.*
import net.minecraft.world.entity.animal.axolotl.Axolotl
import net.minecraft.world.entity.animal.cow.MushroomCow
import net.minecraft.world.entity.animal.equine.Horse
import net.minecraft.world.entity.animal.fish.TropicalFish
import net.minecraft.world.entity.animal.fox.Fox
import net.minecraft.world.entity.animal.parrot.Parrot
import net.minecraft.world.entity.animal.rabbit.Rabbit
import net.minecraft.world.entity.animal.sheep.Sheep
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.*
import kotlin.random.Random
import net.minecraft.world.entity.animal.equine.Variant as HorseVariant

internal data class BiomeRelocation(
	val biome: ResourceKey<Biome>,
	val x: Double,
	val z: Double,
	val tempBucket: Int,
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
 * Builds a diverse relocation pool using [ServerLevel.findClosestBiome3d], which
 * samples the noise biome source without loading chunks - far faster than a grid scan.
 * Results are cached per dimension and reused across mobs.
 */
internal fun AutoCapture.buildPoolForCurrentDimension(mc: Minecraft) {
	val cached = cachedPools[currentDimension]
	if (cached != null) {
		relocationPool = cached
		poolBuildDone = true
		return
	}

	val server = mc.singleplayerServer ?: run { poolBuildDone = true; return }
	val radius = ConfigHolder.config.biomeSearchRadius
	poolBuildDone = false

	server.execute {
		val sLevel = server.getLevel(currentDimensionKey) ?: run { poolBuildDone = true; return@execute }
		val registry = sLevel.registryAccess().lookupOrThrow(Registries.BIOME)
		val origin = BlockPos(baseX, 64, baseZ)
		val pool = mutableListOf<BiomeRelocation>()

		registry.listElements().forEach { biomeHolder ->
			val biomeKey = biomeHolder.unwrapKey().orElse(null) ?: return@forEach
			try {
				val result = sLevel.findClosestBiome3d({ it == biomeHolder }, origin, radius, 32, 32)
				if (result != null) {
					val pos = result.first
					pool.add(
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

		relocationPool = pool.shuffled()
		cachedPools[currentDimension] = relocationPool
		poolBuildDone = true
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

private fun AutoCapture.netherFloorY(mc: Minecraft, x: Int, z: Int): Double {
	val level = mc.level ?: return 40.0
	for (y in 110 downTo 5) {
		if (!level.getBlockState(BlockPos(x, y, z)).isAir &&
			level.getBlockState(BlockPos(x, y + 1, z)).isAir
		) {
			val clearOk = (1..3).all { dy -> level.getBlockState(BlockPos(x, y + dy, z)).isAir }
			if (clearOk) return (y + 1).toDouble()
		}
	}
	return 40.0
}

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

	for (i in 0 until 30) {
		val x = centerX + Random.nextInt(-radius, radius + 1)
		val z = centerZ + Random.nextInt(-radius, radius + 1)
		val bp = BlockPos(x.toInt(), 0, z.toInt())
		if (!level.isLoaded(bp)) continue

		val y = if (currentDimension == MobDimension.NETHER) {
			netherFloorY(mc, x.toInt(), z.toInt()).also { if (it < 5.0) continue }
		} else {
			val blocking = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x.toInt(), z.toInt())
			val noLeaves = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x.toInt(), z.toInt())
			if (blocking > noLeaves + 1) continue
			noLeaves.toDouble()
		}

		val neededClear = ceil(mobHeight).toInt() + 1
		val verticalClear = (0 until neededClear).all { dy ->
			level.getBlockState(BlockPos(x.toInt(), y.toInt() + dy, z.toInt())).isAir
		}
		if (!verticalClear) continue

		val hw = (currentMobEntityType?.width ?: 0.6f) / 2f + 0.1f
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
	entity.isInvulnerable = true
	entity.clearFire()
	(entity as? Mob)?.isNoAi = true
	(entity as? LivingEntity)?.addEffect(
		MobEffectInstance(MobEffects.FIRE_RESISTANCE, -1, 0, false, false, false)
	)
	entity.addTag(MOB_TAG)
	entity.setGlowingTag(true)

	if (cfg.babyAndVariants) {
		if (Random.nextBoolean()) (entity as? AgeableMob)?.isBaby = true
		applyRandomVariant(entity)
	}

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

private fun applyRandomVariant(entity: Entity) {
	when (entity) {
		is Sheep -> entity.setColor(DyeColor.entries.random())
		is Horse -> setVariantReflect(entity, HorseVariant.entries.random())
		is Rabbit -> setVariantReflect(entity, Rabbit.Variant.entries.filter { it != Rabbit.Variant.EVIL }.random())
		is Parrot -> setVariantReflect(entity, Parrot.Variant.entries.random())
		is Axolotl -> setVariantReflect(entity, Axolotl.Variant.entries.random())
		is Fox -> setVariantReflect(entity, Fox.Variant.entries.random())
		is TropicalFish -> setPackedVariantReflect(entity, TropicalFish.COMMON_VARIANTS.random().packedId)
		is MushroomCow -> setVariantReflect(
			entity,
			MushroomCow.Variant.entries.filter { it != MushroomCow.Variant.DEFAULT }.random()
		)
	}
}

private fun setVariantReflect(entity: Any, variant: Any) {
	runCatching {
		val method = entity.javaClass.declaredMethods.first { it.name == "setVariant" && it.parameterCount == 1 }
		method.isAccessible = true
		method.invoke(entity, variant)
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
	repeat(40) {
		val x = centerX + Random.nextDouble(-radius.toDouble(), radius.toDouble())
		val z = centerZ + Random.nextDouble(-radius.toDouble(), radius.toDouble())
		if (!level.isLoaded(BlockPos(x.toInt(), 0, z.toInt()))) return@repeat
		val surfY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x.toInt(), z.toInt())
		for (y in surfY downTo maxOf(surfY - 30, 20)) {
			if (level.getFluidState(BlockPos(x.toInt(), y, z.toInt())).`is`(FluidTags.WATER)) {
				val depth = Random.nextInt(1, 4)
				return Triple(x, (y - depth).toDouble(), z)
			}
		}
	}
	return null
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
