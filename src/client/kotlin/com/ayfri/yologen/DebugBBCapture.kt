package com.ayfri.yologen

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.GameType
import kotlin.math.atan2

data object DebugBBCapture {
    private const val SPAWN_WAIT_TICKS = 40
    private const val MOB_Y = 200.0
    private const val MOB_DIST = 5.0

    var running = false
        private set
    private var mobIndex = 0
    private var subTick = 0
    private var baseX = 0.0
    private var baseZ = 0.0

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (running) tick(mc)
        }
    }

    fun start(mc: Minecraft) {
        if (AutoCapture.running) {
            mc.player?.sendSystemMessage(Component.literal("§c[YoloGen] AutoCapture is running - /yolostop first."))
            return
        }
        baseX = mc.player?.x ?: 0.0
        baseZ = mc.player?.z ?: 0.0
        mobIndex = 0
        subTick = 0
        running = true
        DatasetCapture.autoMode = false
        DatasetCapture.debugBBMode = true
        AutoCapture.setGameRulesDirect(mc, spawnMobs = false, advTime = false, advWeather = false, randomTick = 0)
        mc.player?.sendSystemMessage(Component.literal("§e[YoloGen] §fDebug BB: ${MOB_ENTRIES.size} mobs → §7dataset/debug/"))
    }

    fun stop(mc: Minecraft) {
        running = false
        DatasetCapture.debugBBMode = false
        DatasetCapture.autoMode = true
        AutoCapture.setGameRulesDirect(mc, spawnMobs = true, advTime = true, advWeather = true, randomTick = 3)
        // Kill any leftover mob
        mc.singleplayerServer?.execute {
            mc.singleplayerServer?.overworld()?.allEntities
                ?.filter { it !is ServerPlayer }
                ?.forEach { it.discard() }
        }
        mc.player?.sendSystemMessage(Component.literal("§a[YoloGen] §fDebug BB done — $mobIndex/${MOB_ENTRIES.size} mobs."))
    }

    private fun currentMobName() =
        BuiltInRegistries.ENTITY_TYPE.getKey(MOB_ENTRIES[mobIndex].entityType).toString().substringAfter(':')

    private fun tick(mc: Minecraft) {
        if (mobIndex >= MOB_ENTRIES.size) {
            stop(mc)
            return
        }
        when {
            subTick == 0 -> spawnAndPosition(mc)
            subTick < SPAWN_WAIT_TICKS -> subTick++
            subTick == SPAWN_WAIT_TICKS -> triggerCapture()
            subTick >= SPAWN_WAIT_TICKS + 2 -> {
                mobIndex++
                subTick = 0
            }
            else -> subTick++
        }
    }

    private fun spawnAndPosition(mc: Minecraft) {
        val entry = MOB_ENTRIES[mobIndex]
        val mobX = baseX
        val mobZ = baseZ + MOB_DIST

        val server = mc.singleplayerServer
        if (server != null) {
            server.execute {
                val sLevel = server.overworld()
                sLevel.allEntities.filter { it !is ServerPlayer }.forEach { it.discard() }

                val entity = entry.entityType.create(sLevel, EntitySpawnReason.COMMAND) ?: return@execute
                // yaw=180 so mob faces toward the player (north, -Z)
                entity.snapTo(mobX, MOB_Y, mobZ, 180f, 0f)
                entity.isInvulnerable = true
                entity.clearFire()
                (entity as? Mob)?.isNoAi = true
                (entity as? LivingEntity)?.addEffect(
                    MobEffectInstance(MobEffects.FIRE_RESISTANCE, -1, 0, false, false, false)
                )
                sLevel.addFreshEntity(entity)

                val sp = AutoCapture.serverPlayer(mc)
                sp?.gameMode?.changeGameModeForPlayer(GameType.SPECTATOR)
                sp?.teleportTo(baseX, MOB_Y, baseZ)
            }
        } else {
            val conn = mc.player?.connection ?: return
            val regName = BuiltInRegistries.ENTITY_TYPE.getKey(entry.entityType).toString()
            conn.sendCommand("kill @e[type=!player]")
            conn.sendCommand(
                "summon $regName ${mobX.fmt()} $MOB_Y ${mobZ.fmt()} " +
                    "{Invulnerable:1b,NoAI:1b,Rotation:[180f,0f]," +
                    "active_effects:[{id:\"minecraft:fire_resistance\",duration:-1,amplifier:0," +
                    "ambient:0b,show_particles:0b,show_icon:0b}]}"
            )
            conn.sendCommand("gamemode spectator")
            conn.sendCommand("tp @s ${baseX.fmt()} $MOB_Y ${baseZ.fmt()}")
        }

        // Client-side camera: player at (baseX, MOB_Y, baseZ) looking south (+Z) at the mob
        val player = mc.player ?: return
        val eyeY = MOB_Y + 1.62
        val mobCenterY = MOB_Y + (entry.entityType.height / 2.0)
        val pitch = (-Math.toDegrees(atan2(mobCenterY - eyeY, MOB_DIST))).toFloat()
        player.snapTo(baseX, MOB_Y, baseZ, 0f, pitch)

        mc.player?.sendSystemMessage(
            Component.literal("§7[DebugBB] §f${currentMobName()} §8(${mobIndex + 1}/${MOB_ENTRIES.size})")
        )
        subTick = 1
    }

    private fun triggerCapture() {
        DatasetCapture.pendingCaptureMetadata = CaptureMetadata(
            mobName = currentMobName(),
            mobX = baseX, mobY = MOB_Y, mobZ = baseZ + MOB_DIST,
            weather = "clear", timeOfDay = 6000,
            shotIndex = 0, mobIndex = mobIndex,
        )
        DatasetCapture.pendingCapture = true
        subTick++
    }
}
