package com.ayfri.yologen

import com.ayfri.yologen.config.ConfigHolder
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.*
import kotlin.random.Random

internal fun AutoCapture.orbitParams(shotIdx: Int): Triple<Double, Double, Double> {
	val cfg = ConfigHolder.config
	val tier = shotIdx / TIER_SIZE
	val relocateEvery = cfg.relocateEvery
	val baseAngle = (shotIdx % relocateEvery).toDouble() / relocateEvery * 2 * PI
	val jitter = if (cfg.cameraJitterAndLighting) cfg.cameraJitterDegrees.toDouble() * PI / 180.0 else PI / relocateEvery.toDouble()
	val angle = baseAngle + Random.nextDouble(-jitter, jitter)
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

internal fun AutoCapture.applyRotation(mc: Minecraft) {
	val p = mc.player ?: return
	p.yRot = targetYaw; p.yRotO = targetYaw; p.setYHeadRot(targetYaw); p.setYBodyRot(targetYaw)
	p.xRot = targetPitch; p.xRotO = targetPitch
}

internal fun AutoCapture.recomputeAndApplyRotation(mc: Minecraft) {
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

internal fun AutoCapture.updateMobPosition(mc: Minecraft) {
	val entities = mc.level?.entitiesForRendering()?.filterIsInstance<LivingEntity>()
		?.filter { it != mc.player } ?: return
	val nearest = entities.minByOrNull { (it.x - mobX).pow(2) + (it.z - mobZ).pow(2) } ?: return
	val dist2 = (nearest.x - mobX).pow(2) + (nearest.z - mobZ).pow(2)
	if (dist2 < 900.0) { mobX = nearest.x; mobY = nearest.y; mobZ = nearest.z }
}

/**
 * Multi-ray visibility test: casts rays to the mob's head, centre, feet, and sides.
 * Requires at least 2 of the rays to be unobstructed before declaring the mob visible.
 */
internal fun AutoCapture.isVisible(mc: Minecraft): Boolean {
	val player = mc.player ?: return true
	val level = mc.level ?: return true
	val eye = player.eyePosition
	val hw = (currentMobEntityType?.width ?: 0.6f) / 2.0
	val targets = listOf(
		Vec3(mobX, mobY + (currentMobEntityType?.height ?: 1.8f) * 0.9, mobZ),
		Vec3(mobX, mobY + (currentMobEntityType?.height ?: 1.8f) * 0.5, mobZ),
		Vec3(mobX, mobY + 0.1, mobZ),
		Vec3(mobX + hw, mobY + (currentMobEntityType?.height ?: 1.8f) * 0.5, mobZ),
		Vec3(mobX - hw, mobY + (currentMobEntityType?.height ?: 1.8f) * 0.5, mobZ),
	)
	val clearCount = targets.count { target ->
		val hit = level.clip(ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
		hit.type != HitResult.Type.BLOCK
	}
	return clearCount >= 2
}
