package com.ayfri.yologen

import net.minecraft.world.entity.EntityType

enum class MobDimension { OVERWORLD, NETHER, END }

// Ordered list - index IS the YOLO class ID. Never reorder existing entries; append new mobs at the end of their section.
// dimensions: shots are split equally across listed dims (e.g. 2 dims = 50%/50%, 3 = 33%/33%/34%)
data class MobEntry(
	val entityType: EntityType<*>,
	val dimensions: List<MobDimension> = listOf(MobDimension.OVERWORLD),
	val aquatic: Boolean = false,
)

private fun mob(type: EntityType<*>, vararg dims: MobDimension) =
	MobEntry(type, if (dims.isEmpty()) listOf(MobDimension.OVERWORLD) else dims.toList())

private fun aqua(type: EntityType<*>) =
	MobEntry(type, listOf(MobDimension.OVERWORLD), aquatic = true)

val MOB_ENTRIES = listOf(
	// Hostile
	mob(EntityType.BLAZE, MobDimension.OVERWORLD, MobDimension.NETHER),
	mob(EntityType.BOGGED),
	mob(EntityType.BREEZE),
	mob(EntityType.CAVE_SPIDER),
	mob(EntityType.CREAKING),
	mob(EntityType.CREEPER),
	mob(EntityType.DROWNED),
	aqua(EntityType.ELDER_GUARDIAN),
	mob(EntityType.ENDERMAN, MobDimension.OVERWORLD, MobDimension.NETHER, MobDimension.END),
	mob(EntityType.ENDERMITE, MobDimension.OVERWORLD, MobDimension.END),
	mob(EntityType.EVOKER),
	mob(EntityType.GHAST, MobDimension.OVERWORLD, MobDimension.NETHER),
	aqua(EntityType.GUARDIAN),
	mob(EntityType.HOGLIN, MobDimension.OVERWORLD, MobDimension.NETHER),
	mob(EntityType.HUSK),
	mob(EntityType.ILLUSIONER),
	mob(EntityType.MAGMA_CUBE, MobDimension.OVERWORLD, MobDimension.NETHER),
	mob(EntityType.PARCHED),
	mob(EntityType.PHANTOM),
	mob(EntityType.PIGLIN, MobDimension.OVERWORLD, MobDimension.NETHER),
	mob(EntityType.PIGLIN_BRUTE, MobDimension.OVERWORLD, MobDimension.NETHER),
	mob(EntityType.PILLAGER),
	mob(EntityType.RAVAGER),
	mob(EntityType.SHULKER, MobDimension.OVERWORLD, MobDimension.END),
	mob(EntityType.SILVERFISH),
	mob(EntityType.SKELETON),
	mob(EntityType.SLIME),
	mob(EntityType.SPIDER),
	mob(EntityType.STRAY),
	mob(EntityType.VEX),
	mob(EntityType.VINDICATOR),
	mob(EntityType.WARDEN),
	mob(EntityType.WITCH),
	mob(EntityType.WITHER_SKELETON, MobDimension.OVERWORLD, MobDimension.NETHER),
	mob(EntityType.ZOGLIN, MobDimension.OVERWORLD, MobDimension.NETHER),
	mob(EntityType.ZOMBIE),
	mob(EntityType.ZOMBIE_VILLAGER),
	mob(EntityType.ZOMBIFIED_PIGLIN, MobDimension.OVERWORLD, MobDimension.NETHER),

	// Neutral
	mob(EntityType.BEE),
	aqua(EntityType.DOLPHIN),
	mob(EntityType.GOAT),
	mob(EntityType.IRON_GOLEM),
	mob(EntityType.LLAMA),
	mob(EntityType.PANDA),
	mob(EntityType.POLAR_BEAR),
	mob(EntityType.STRIDER, MobDimension.OVERWORLD, MobDimension.NETHER),
	mob(EntityType.TRADER_LLAMA),
	mob(EntityType.WOLF),

	// Passive
	mob(EntityType.ALLAY),
	mob(EntityType.ARMADILLO),
	aqua(EntityType.AXOLOTL),
	mob(EntityType.BAT),
	mob(EntityType.CAMEL),
	mob(EntityType.CAMEL_HUSK),
	mob(EntityType.CAT),
	mob(EntityType.CHICKEN),
	aqua(EntityType.COD),
	mob(EntityType.COW),
	mob(EntityType.DONKEY),
	mob(EntityType.FOX),
	mob(EntityType.FROG),
	aqua(EntityType.GLOW_SQUID),
	mob(EntityType.HAPPY_GHAST, MobDimension.OVERWORLD, MobDimension.NETHER),
	mob(EntityType.HORSE),
	mob(EntityType.MOOSHROOM),
	mob(EntityType.MULE),
	aqua(EntityType.NAUTILUS),
	mob(EntityType.OCELOT),
	mob(EntityType.PARROT),
	mob(EntityType.PIG),
	aqua(EntityType.PUFFERFISH),
	mob(EntityType.RABBIT),
	aqua(EntityType.SALMON),
	mob(EntityType.SHEEP),
	mob(EntityType.SKELETON_HORSE),
	mob(EntityType.SNIFFER),
	mob(EntityType.SNOW_GOLEM),
	aqua(EntityType.SQUID),
	aqua(EntityType.TADPOLE),
	aqua(EntityType.TROPICAL_FISH),
	mob(EntityType.TURTLE),
	mob(EntityType.ZOMBIE_HORSE),
	aqua(EntityType.ZOMBIE_NAUTILUS),

	// Bosses & Golems
	mob(EntityType.COPPER_GOLEM),
	mob(EntityType.ENDER_DRAGON, MobDimension.OVERWORLD, MobDimension.END),
	mob(EntityType.GIANT),
	mob(EntityType.WITHER),
)

// Derived - index = YOLO class ID. Must preserve order of MOB_ENTRIES.
val MOB_TYPES = MOB_ENTRIES.map { it.entityType }
val YOLO_CLASS_MAP = MOB_TYPES.mapIndexed { i, type -> type to i }.toMap()
