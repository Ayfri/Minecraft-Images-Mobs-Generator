package com.ayfri.yologen

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.InactivityFpsLimit
import net.minecraft.client.Minecraft
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2

data object DebugBBCapture {
    private const val SPAWN_WAIT_TICKS = 3
    private const val MOB_Y = 200.0
    private const val BASE_DIST = 5.0

    var running = false
        private set

    @JvmStatic
    fun isRunning(): Boolean = running

    private var mobIndex = 0
    private var subTick = 0
    private var baseX = 0.0
    private var baseZ = 0.0
    private var currentMobZ = 0.0

    private var targetYaw = 0f
    private var targetPitch = 0f

    private var savedVsync = true
    private var savedFramerateLimit = 120
    private var savedInactivityFpsLimit: InactivityFpsLimit = InactivityFpsLimit.MINIMIZED
    private var savedTickRate = 20f

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
        setGameRulesDirect(mc, spawnMobs = false, advTime = false, advWeather = false, randomTick = 0)

        savedVsync = mc.options.enableVsync().get()
        mc.options.enableVsync().set(false)
        savedFramerateLimit = mc.options.framerateLimit().get()
        mc.options.framerateLimit().set(260)
        savedInactivityFpsLimit = mc.options.inactivityFpsLimit().get()
        mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED)
        val server = mc.singleplayerServer
        if (server != null) {
            savedTickRate = server.tickRateManager().tickrate()
            server.execute { server.tickRateManager().setTickRate(100f) }
        }
        applyInstantTime(mc, 6000L)

        mc.player?.sendSystemMessage(Component.literal("§e[YoloGen] §fDebug BB: ${MOB_ENTRIES.size} mobs → §7dataset/debug/"))
    }

    fun stop(mc: Minecraft) {
        running = false
        DatasetCapture.debugBBMode = false
        DatasetCapture.autoMode = true
        setGameRulesDirect(mc, spawnMobs = true, advTime = true, advWeather = true, randomTick = 3)

        mc.options.enableVsync().set(savedVsync)
        mc.options.framerateLimit().set(savedFramerateLimit)
        mc.options.inactivityFpsLimit().set(savedInactivityFpsLimit)
        val server = mc.singleplayerServer
        if (server != null) {
            server.execute { server.tickRateManager().setTickRate(savedTickRate) }
            savedTickRate = 20f
        }

        // Kill any leftover mob
        mc.singleplayerServer?.execute {
            mc.singleplayerServer?.overworld()?.allEntities
                ?.filter { it !is ServerPlayer }
                ?.forEach { it.discard() }
        }
        mc.player?.sendSystemMessage(Component.literal("§a[YoloGen] §fDebug BB done - $mobIndex/${MOB_ENTRIES.size} mobs."))
    }

    private fun currentMobName() =
        BuiltInRegistries.ENTITY_TYPE.getKey(MOB_ENTRIES[mobIndex].entityType).toString().substringAfter(':')

    private fun tick(mc: Minecraft) {
        if (mobIndex >= MOB_ENTRIES.size) {
            stop(mc)
            return
        }
        // Keep camera locked on the mob every tick — server teleportTo can override rotation otherwise.
        if (subTick > 0) {
            mc.player?.let { p ->
                p.yRot = targetYaw; p.yRotO = targetYaw
                p.setYHeadRot(targetYaw); p.setYBodyRot(targetYaw)
                p.xRot = targetPitch; p.xRotO = targetPitch
            }
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
        val scale = (maxOf(entry.entityType.height, entry.entityType.width * 2f) / 1.8).coerceIn(0.4, 7.0)
        val dist = BASE_DIST * scale
        val mobX = baseX
        val mobZ = baseZ + dist
        currentMobZ = mobZ

        // Camera eye position — used both for pitch and for entity lookAt target.
        val eyeY = MOB_Y + 1.62
        val mobCenterY = MOB_Y + (entry.entityType.height / 2.0)

        val server = mc.singleplayerServer
        if (server != null) {
            // Capture camera eye as a Vec3 so the lambda can reference it.
            val cameraEye = Vec3(baseX, eyeY, baseZ)
            server.execute {
                val sLevel = server.overworld()
                sLevel.allEntities.filter { it !is ServerPlayer }.forEach { it.discard() }

                val entity = entry.entityType.create(sLevel, EntitySpawnReason.COMMAND) ?: return@execute
                entity.snapTo(mobX, MOB_Y, mobZ, 0f, 0f)
                // lookAt geometrically computes the correct yaw/pitch to face the camera,
                // regardless of MC's yaw=0 convention.
                entity.lookAt(EntityAnchorArgument.Anchor.EYES, cameraEye)
                entity.yRotO = entity.yRot
                entity.setYHeadRot(entity.yRot)
                entity.setYBodyRot(entity.yRot)

                entity.isInvulnerable = true
                entity.clearFire()
                (entity as? Mob)?.isNoAi = true
                (entity as? LivingEntity)?.addEffect(
                    MobEffectInstance(MobEffects.FIRE_RESISTANCE, -1, 0, false, false, false)
                )
                entity.setGlowingTag(true)
                sLevel.addFreshEntity(entity)

                val sp = serverPlayer(mc)
                sp?.gameMode?.changeGameModeForPlayer(GameType.SPECTATOR)
                sp?.teleportTo(baseX, MOB_Y, baseZ)
            }
        } else {
            val conn = mc.player?.connection ?: return
            val regName = BuiltInRegistries.ENTITY_TYPE.getKey(entry.entityType).toString()
            // Compute yaw for the mob to face the camera (baseX, baseZ) from (mobX, mobZ).
            val mobFaceYaw = Math.toDegrees(atan2(-(baseX - mobX), baseZ - mobZ)).toFloat()
            conn.sendCommand("kill @e[type=!player]")
            conn.sendCommand(
                "summon $regName ${mobX.fmt()} $MOB_Y ${mobZ.fmt()} " +
                    "{Invulnerable:1b,NoAI:1b,Glowing:1b,Rotation:[${mobFaceYaw}f,0f]," +
                    "active_effects:[{id:\"minecraft:fire_resistance\",duration:-1,amplifier:0," +
                    "ambient:0b,show_particles:0b,show_icon:0b}]}"
            )
            conn.sendCommand("gamemode spectator")
            conn.sendCommand("tp @s ${baseX.fmt()} $MOB_Y ${baseZ.fmt()}")
        }

        // Camera: compute yaw/pitch from camera position toward mob center.
        val dx = mobX - baseX
        val dz = mobZ - baseZ
        targetYaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        targetPitch = (-Math.toDegrees(atan2(mobCenterY - eyeY, dist))).toFloat()

        val player = mc.player ?: return
        player.snapTo(baseX, MOB_Y, baseZ, targetYaw, targetPitch)

        mc.player?.sendSystemMessage(
            Component.literal("§7[DebugBB] §f${currentMobName()} §8(${mobIndex + 1}/${MOB_ENTRIES.size})")
        )
        subTick = 1
    }

    private fun triggerCapture() {
        DatasetCapture.debugClassId = YOLO_CLASS_MAP[MOB_ENTRIES[mobIndex].entityType]
        DatasetCapture.pendingCaptureMetadata = CaptureMetadata(
            mobName = currentMobName(),
            mobX = baseX, mobY = MOB_Y, mobZ = currentMobZ,
            weather = "clear", timeOfDay = 6000,
            shotIndex = 0, mobIndex = mobIndex,
        )
        DatasetCapture.pendingCapture = true
        subTick++
    }
}
