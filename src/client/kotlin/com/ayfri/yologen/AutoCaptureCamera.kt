package com.ayfri.yologen

import com.ayfri.yologen.config.ConfigHolder
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.*
import kotlin.random.Random

/** Scale factor for orbit distances derived from mob AABB size vs reference (zombie ~1.8m tall). */
internal val AutoCapture.mobSizeScale: Double
	get() {
		val type = currentMobEntityType ?: return 1.0
		// Max of height and 2×width so wide mobs (Ender Dragon, Ghast) also step back.
		val effectiveSize = maxOf(type.height, type.width * 2f).toDouble()
		return (effectiveSize / 1.8).coerceIn(0.4, 7.0)
	}

/**
 * Camera profiles as (distMin, distMax, heightMin, heightMax), all multiplied by mobSizeScale.
 * Ordered roughly close→far, overhead last. Distances are deliberately tight: most frames
 * should have the mob filling a good chunk of the image (the intended use is internet
 * images/videos, not surveillance-from-above shots).
 */
private val ORBIT_PROFILES = arrayOf(
	doubleArrayOf(1.8, 3.5, 0.2, 1.2),   // 0 close, eye level
	doubleArrayOf(2.2, 4.0, 0.0, 1.6),   // 1 close, dynamic height
	doubleArrayOf(3.5, 5.5, 0.5, 2.0),   // 2 medium
	doubleArrayOf(4.5, 7.5, 1.5, 3.5),   // 3 medium, slightly high
	doubleArrayOf(7.0, 12.0, 0.8, 3.0),  // 4 far
	doubleArrayOf(2.5, 5.0, 6.0, 11.0),  // 5 overhead (rare)
)

/**
 * Weighted cycle over [ORBIT_PROFILES], indexed by tier. Close shots (0,1) dominate,
 * far (4) and overhead (5) appear once per 10 tiers. Repeats cleanly for any shotsPerMob,
 * so the distribution no longer degrades into all-overhead past shot 150.
 */
private val ORBIT_CYCLE = intArrayOf(0, 1, 2, 0, 1, 3, 0, 2, 4, 5)

/** Shots per tier; 20 × 10-entry ORBIT_CYCLE = one clean pass per 200-shot mob. */
private const val TIER_SIZE = 20

internal fun AutoCapture.orbitParams(shotIdx: Int, retry: Int = 0): Triple<Double, Double, Double> {
	if (currentMobIsAquatic) return aquaticOrbitParams(shotIdx)
	val cfg = ConfigHolder.config
	val s = mobSizeScale
	val relocateEvery = cfg.relocateEvery
	val baseAngle = (shotIdx % relocateEvery).toDouble() / relocateEvery * 2 * PI
	val jitter =
		if (cfg.cameraJitterAndLighting) cfg.cameraJitterDegrees.toDouble() * PI / 180.0 else PI / relocateEvery.toDouble()
	val angle = baseAngle + Random.nextDouble(-jitter, jitter)
	// After repeated occlusion failures, pull the camera in close and to eye level.
	// Close eye-level framings are almost never blocked by trees/terrain, so the shot
	// recovers instead of being skipped without a saved image.
	if (retry >= 4) {
		return Triple(angle, Random.nextDouble(2.0, 3.5) * s, Random.nextDouble(0.1, 1.0) * s)
	}

	val p = ORBIT_PROFILES[ORBIT_CYCLE[shotIdx / TIER_SIZE % ORBIT_CYCLE.size]]
	return Triple(angle, Random.nextDouble(p[0], p[1]) * s, Random.nextDouble(p[2], p[3]) * s)
}

/**
 * Orbit params for aquatic mobs: camera stays inside the water column.
 * heightOffset is relative to mobY, so negative = below mob, positive = above.
 * The angles cover: side/eye-level, below looking up, shallow above, and
 * a few shots from just above the water surface looking down.
 */
private fun AutoCapture.aquaticOrbitParams(shotIdx: Int): Triple<Double, Double, Double> {
	val cfg = ConfigHolder.config
	val s = mobSizeScale
	val relocateEvery = cfg.relocateEvery
	val baseAngle = (shotIdx % relocateEvery).toDouble() / relocateEvery * 2 * PI
	val jitter =
		if (cfg.cameraJitterAndLighting) cfg.cameraJitterDegrees.toDouble() * PI / 180.0 else PI / relocateEvery.toDouble()
	val angle = baseAngle + Random.nextDouble(-jitter, jitter)
	return when (shotIdx % 7) {
		0 -> Triple(angle, Random.nextDouble(2.0, 4.0) * s, Random.nextDouble(-2.5, -0.5) * s)
		1 -> Triple(angle, Random.nextDouble(3.0, 6.0) * s, Random.nextDouble(-0.5, 0.5) * s)
		2 -> Triple(angle, Random.nextDouble(2.5, 5.0) * s, Random.nextDouble(0.5, 2.0) * s)
		3 -> Triple(angle, Random.nextDouble(4.0, 8.0) * s, Random.nextDouble(-3.5, -1.0) * s)
		4 -> Triple(angle, Random.nextDouble(1.5, 3.0) * s, Random.nextDouble(-4.0, -2.0) * s)
		5 -> Triple(angle, Random.nextDouble(5.0, 10.0) * s, Random.nextDouble(2.0, 5.0) * s)
		else -> Triple(angle, Random.nextDouble(3.0, 6.0) * s, Random.nextDouble(-1.5, 1.5) * s)
	}
}

internal fun AutoCapture.applyRotation(mc: Minecraft) {
	val p = mc.player ?: return
	p.yRot = targetYaw; p.yRotO = targetYaw; p.setYHeadRot(targetYaw); p.setYBodyRot(targetYaw)
	p.xRot = targetPitch; p.xRotO = targetPitch
}

internal fun AutoCapture.recomputeAndApplyRotation(mc: Minecraft, yawOffset: Float = 0f, pitchOffset: Float = 0f) {
	val p = mc.player ?: return
	val dx = mobX - p.x
	val dy = (mobY + 1.0) - p.eyeY
	val dz = mobZ - p.z
	val h = sqrt(dx * dx + dz * dz)
	if (h < 0.01 && abs(dy) < 0.01) return
	targetYaw = Math.toDegrees(atan2(-dx, dz)).toFloat() + yawOffset
	targetPitch = (-Math.toDegrees(atan2(dy, h))).toFloat() + pitchOffset
	applyRotation(mc)
}

internal fun AutoCapture.updateMobPosition(mc: Minecraft) {
	val entities = mc.level?.entitiesForRendering()?.filterIsInstance<LivingEntity>()
		?.filter { it != mc.player } ?: return
	val nearest = entities.minByOrNull { (it.x - mobX).pow(2) + (it.z - mobZ).pow(2) } ?: return
	val dist2 = (nearest.x - mobX).pow(2) + (nearest.z - mobZ).pow(2)
	if (dist2 < 900.0) {
		mobX = nearest.x; mobY = nearest.y; mobZ = nearest.z
	}
}

/**
 * Direct visibility raycast: casts rays to the mob's head, centre, feet, and sides.
 * Requires the direct centre ray to be unobstructed AND at least 2 more of the surrounding
 * rays to be clear, so partially-buried or wall-occluded mobs are rejected (they would
 * otherwise be picked up through the glow silhouette, producing frames with no visible mob).
 */
internal fun AutoCapture.isVisible(mc: Minecraft): Boolean {
	val player = mc.player ?: return true
	val level = mc.level ?: return true
	val eye = player.eyePosition
	val height = (currentMobEntityType?.height ?: 1.8f)
	val hw = (currentMobEntityType?.width ?: 0.6f) / 2.0
	val center = Vec3(mobX, mobY + height * 0.5, mobZ)

	fun clear(target: Vec3): Boolean {
		val hit = level.clip(ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
		return hit.type != HitResult.Type.BLOCK
	}

	// The mob centre must be directly visible.
	if (!clear(center)) return false

	val others = listOf(
		Vec3(mobX, mobY + height * 0.9, mobZ),
		Vec3(mobX, mobY + 0.1, mobZ),
		Vec3(mobX + hw, mobY + height * 0.5, mobZ),
		Vec3(mobX - hw, mobY + height * 0.5, mobZ),
	)
	return others.count { clear(it) } >= 2
}
