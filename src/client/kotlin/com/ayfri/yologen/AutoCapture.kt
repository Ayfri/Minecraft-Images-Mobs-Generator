package com.ayfri.yologen

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import kotlin.math.*
import kotlin.random.Random

object AutoCapture {
    private const val SHOTS_PER_MOB = 100
    private const val SETUP_WAIT_TICKS = 40
    private const val MAX_DIST = 12.0
    private const val MIN_DIST = 3.0

    private var running = false
    private var phase = Phase.IDLE
    private var setupTick = 0
    private var shotCount = 0
    private var subTick = 0

    private var mobX = 0.0
    private var mobY = 64.0
    private var mobZ = 0.0

    private enum class Phase { IDLE, SETUP, WAITING, CAPTURING }

    private val mobTypes = YOLO_CLASS_MAP.keys.toList()

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
    }

    private fun start(mc: Minecraft) {
        running = true
        phase = Phase.SETUP
        setupTick = 0
        shotCount = 0
        subTick = 0
        DatasetCapture.autoMode = false
        mc.player?.displayClientMessage(Component.literal("[YoloGen] Auto-capture started — /yologen to stop"), false)
    }

    private fun stop(mc: Minecraft) {
        running = false
        phase = Phase.IDLE
        DatasetCapture.autoMode = true
        mc.player?.displayClientMessage(Component.literal("[YoloGen] Auto-capture stopped"), false)
    }

    private fun tick(mc: Minecraft) {
        val player = mc.player ?: return
        val conn = player.connection

        when (phase) {
            Phase.IDLE -> {}

            Phase.SETUP -> {
                if (setupTick == 0) {
                    conn.sendCommand("kill @e[type=!player,distance=..200]")

                    val baseX = Random.nextInt(-500, 500)
                    val baseZ = Random.nextInt(-500, 500)
                    conn.sendCommand("tp @s $baseX 64 $baseZ")
                    conn.sendCommand("time set ${Random.nextInt(0, 24000)}")
                    conn.sendCommand("weather ${listOf("clear", "rain", "thunder").random()}")

                    val mobType = mobTypes.random()
                    val regName = BuiltInRegistries.ENTITY_TYPE.getKey(mobType)?.toString()
                        ?: run { setupTick = 0; return }

                    mobX = baseX + Random.nextInt(-6, 6).toDouble()
                    mobY = 64.0
                    mobZ = baseZ + Random.nextInt(-6, 6).toDouble()

                    conn.sendCommand("summon $regName ${mobX.toInt()} ${mobY.toInt()} ${mobZ.toInt()}")

                    shotCount = 0
                    subTick = 0
                }
                setupTick++
                if (setupTick >= SETUP_WAIT_TICKS) {
                    setupTick = 0
                    phase = Phase.CAPTURING
                }
            }

            Phase.CAPTURING -> {
                when (subTick) {
                    0 -> {
                        val angle = Random.nextDouble(0.0, 2 * PI)
                        val dist = Random.nextDouble(MIN_DIST, MAX_DIST)
                        val heightOffset = Random.nextDouble(-0.5, 4.0)

                        val px = mobX + cos(angle) * dist
                        val py = mobY + heightOffset
                        val pz = mobZ + sin(angle) * dist

                        val dx = mobX - px
                        val dy = mobY + 1.0 - py
                        val dz = mobZ - pz
                        val h = sqrt(dx * dx + dz * dz)
                        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
                        val pitch = (-Math.toDegrees(atan2(dy, h))).toFloat()

                        conn.sendCommand("tp @s ${"%.2f".format(px)} ${"%.2f".format(py)} ${"%.2f".format(pz)} ${"%.1f".format(yaw)} ${"%.1f".format(pitch)}")
                        subTick = 1
                    }

                    1 -> subTick = 2

                    2 -> {
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

            Phase.WAITING -> {}
        }
    }
}
