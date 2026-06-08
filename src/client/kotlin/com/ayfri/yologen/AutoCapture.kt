package com.ayfri.yologen

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.level.levelgen.Heightmap
import java.util.*
import kotlin.math.*
import kotlin.random.Random

object AutoCapture {
	private const val SHOTS_PER_MOB = 100

	// Total ticks for setup. Mob is spawned at MOB_SPAWN_TICK after chunks load.
	private const val SETUP_WAIT_TICKS = 70
	private const val MOB_SPAWN_TICK = 45
	private const val TIER_SIZE = 25

	private var running = false
	private var phase = Phase.IDLE
	private var setupTick = 0
	private var shotCount = 0
	private var subTick = 0
	private var mobIndex = 0
	private var currentMobName = ""

	// Safe baseline height recorded at start, used as fallback when chunks are unloaded.
	private var safeY = 64.0

	private var baseX = 0
	private var baseZ = 0
	private var mobX = 0.0
	private var mobY = 64.0
	private var mobZ = 0.0

	// Set at subTick=0, applied every subsequent tick until capture.
	private var targetYaw = 0f
	private var targetPitch = 0f

	private enum class Phase { IDLE, SETUP, CAPTURING }

	private val mobTypes = YOLO_CLASS_MAP.keys.toList()

	private fun Double.fmt(decimals: Int = 2) = String.format(Locale.ROOT, "%.${decimals}f", this)

	// Returns surface Y, clamped to safeY when the chunk isn't loaded yet (returns 0 or negatives).
	private fun safeSurfaceY(mc: Minecraft, x: Int, z: Int): Double {
		val level = mc.level ?: return safeY
		val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z).toDouble()
		return if (y < 60.0) safeY else y
	}

	// Tier-based orbit: 4×25 shots, each tier covers 360° with evenly-spaced azimuths + jitter.
	// Tier 0: close-ground  Tier 1: medium-mid  Tier 2: far-high  Tier 3: top-down
	private fun orbitParams(shotIdx: Int): Triple<Double, Double, Double> {
		val tier = shotIdx / TIER_SIZE
		val posInTier = shotIdx % TIER_SIZE
		val baseAngle = (posInTier.toDouble() / TIER_SIZE) * 2 * PI
		val angle = baseAngle + Random.nextDouble(-PI / TIER_SIZE, PI / TIER_SIZE)
		return when (tier) {
			0 -> Triple(angle, Random.nextDouble(5.0, 12.0), Random.nextDouble(0.5, 2.5))
			1 -> Triple(angle, Random.nextDouble(10.0, 18.0), Random.nextDouble(2.0, 7.0))
			2 -> Triple(angle, Random.nextDouble(15.0, 25.0), Random.nextDouble(5.0, 14.0))
			else -> Triple(angle, Random.nextDouble(2.0, 8.0), Random.nextDouble(12.0, 22.0))
		}
	}

	// Directly set client-side rotation every tick so the render state captures the right angle.
	// Also sets xRotO/yRotO to avoid interpolation artifacts.
	private fun applyRotation(mc: Minecraft) {
		val p = mc.player ?: return
		p.yRot = targetYaw
		p.xRot = targetPitch
		p.yRotO = targetYaw
		p.xRotO = targetPitch
		p.setYHeadRot(targetYaw)
		p.setYBodyRot(targetYaw)
	}

	fun register() {
		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			dispatcher.register(
				ClientCommands.literal("yologen").executes { ctx ->
					if (running) stop(ctx.source.client) else start(ctx.source.client)
					1
				}
			)
		}

		ClientTickEvents.END_CLIENT_TICK.register { mc ->
			if (running) tick(mc)
		}

		// Progress bar rendered above the hotbar area (top-center of screen).
		HudElementRegistry.addLast(Identifier.parse("yologen:progress"), HudElement { g, _ ->
			if (!running || phase == Phase.IDLE) return@HudElement
			val sw = g.guiWidth()
			val barW = 200
			val barH = 8
			val x = (sw - barW) / 2
			val y = 10

			val progress = if (phase == Phase.CAPTURING) shotCount.toFloat() / SHOTS_PER_MOB else 0f

			// Panel background
			g.fill(x - 2, y - 2, x + barW + 2, y + barH + 2, 0xAA000000.toInt())
			// Filled portion
			g.fill(x, y, x + (barW * progress).toInt(), y + barH, 0xFF22CC22.toInt())
			// Unfilled portion
			g.fill(x + (barW * progress).toInt(), y, x + barW, y + barH, 0xFF333333.toInt())
		})
	}

	private fun start(mc: Minecraft) {
		running = true
		phase = Phase.SETUP
		setupTick = 0
		shotCount = 0
		subTick = 0
		mobIndex = 0
		safeY = mc.player?.y ?: 64.0
		DatasetCapture.autoMode = false
		mc.player?.sendSystemMessage(Component.literal("[YoloGen] Auto-capture started — /yologen to stop"))
	}

	private fun stop(mc: Minecraft) {
		running = false
		phase = Phase.IDLE
		DatasetCapture.autoMode = true
		mc.player?.sendSystemMessage(Component.literal("[YoloGen] Stopped after $mobIndex mobs"))
	}

	private fun tick(mc: Minecraft) {
		val player = mc.player ?: return
		val conn = player.connection

		// Keep action-bar updated during capture (not in screenshots, appears above hotbar).
		if (phase == Phase.CAPTURING) {
			mc.gui.setOverlayMessage(
				Component.literal("§a[YoloGen] §f$currentMobName §8| §7${shotCount + 1}/$SHOTS_PER_MOB shots §8| §7mob #$mobIndex"),
				false
			)
		}

		when (phase) {
			Phase.IDLE -> {}

			Phase.SETUP -> {
				when (setupTick) {
					0 -> {
						// Teleport high (Y=200) so the player isn't underground while chunks load.
						conn.sendCommand("kill @e[type=!player,distance=..200]")
						baseX = Random.nextInt(-500, 500)
						baseZ = Random.nextInt(-500, 500)
						conn.sendCommand("tp @s ${baseX.toDouble().fmt()} 200 ${baseZ.toDouble().fmt()}")
						conn.sendCommand("time set ${Random.nextInt(0, 24000)}")
						conn.sendCommand("weather ${listOf("clear", "rain", "thunder").random()}")
					}

					MOB_SPAWN_TICK -> {
						// Chunks are loaded — land the player and spawn the mob at the real surface.
						val surfY = safeSurfaceY(mc, baseX, baseZ)
						conn.sendCommand("tp @s ${baseX.toDouble().fmt()} ${surfY.fmt()} ${baseZ.toDouble().fmt()}")

						val mobType = mobTypes.random()
						val regName = BuiltInRegistries.ENTITY_TYPE.getKey(mobType)?.toString()
						if (regName != null) {
							currentMobName = regName.substringAfter(':')
							mobX = baseX + Random.nextInt(-5, 5).toDouble()
							mobZ = baseZ + Random.nextInt(-5, 5).toDouble()
							mobY = safeSurfaceY(mc, mobX.toInt(), mobZ.toInt())
							conn.sendCommand("summon $regName ${mobX.fmt()} ${mobY.fmt()} ${mobZ.fmt()}")
						}
						shotCount = 0
						subTick = 0
					}
				}

				setupTick++
				if (setupTick >= SETUP_WAIT_TICKS) {
					setupTick = 0
					mobIndex++
					phase = Phase.CAPTURING
				}
			}

			Phase.CAPTURING -> {
				when (subTick) {
					0 -> {
						val (angle, dist, heightOffset) = orbitParams(shotCount)
						val px = mobX + cos(angle) * dist
						val pz = mobZ + sin(angle) * dist
						val groundY = safeSurfaceY(mc, px.toInt(), pz.toInt())
						val py = maxOf(groundY + heightOffset, mobY + heightOffset)

						val dx = mobX - px
						val dy = (mobY + 1.0) - py
						val dz = mobZ - pz
						val h = sqrt(dx * dx + dz * dz)
						targetYaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
						targetPitch = (-Math.toDegrees(atan2(dy, h))).toFloat()

						// Teleport position only — rotation is applied client-side below.
						conn.sendCommand("tp @s ${px.fmt()} ${py.fmt()} ${pz.fmt()}")
						applyRotation(mc)
						subTick = 1
					}

					1, 2 -> {
						// Re-apply every tick to override any input handler that might reset it.
						applyRotation(mc)
						subTick++
					}

					3 -> {
						applyRotation(mc)
						DatasetCapture.pendingCapture = true
						shotCount++
						subTick = 0

						if (shotCount >= SHOTS_PER_MOB) {
							phase = Phase.SETUP
							setupTick = 0
						}
					}
				}
			}
		}
	}
}
