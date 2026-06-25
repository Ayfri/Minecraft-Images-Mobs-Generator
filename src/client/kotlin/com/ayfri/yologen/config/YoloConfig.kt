package com.ayfri.yologen.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import net.minecraft.client.Minecraft
import java.io.File

data class YoloConfig(
	/** Screenshots taken per mob (split evenly across its dimensions). */
	val shotsPerMob: Int = 300,
	/** Render distance (chunks) set while capturing; restored on stop. */
	val captureRenderDistance: Int = 8,
	/** Move mob+camera to a new biome location every N shots. */
	val relocateEvery: Int = 10,
	/** In-game time ticks advanced per shot (for day/night progression). */
	val timePerShot: Long = 400L,
	/** Radius (blocks) searched when building the biome relocation pool. */
	val biomeSearchRadius: Int = 2000,
	/** Server tick rate set while capturing (≥ 20); restored on stop. */
	val captureTickRate: Float = 100f,

	val weatherClearFraction: Float = 0.60f,
	val weatherRainFraction: Float = 0.20f,
	// weatherThunderFraction is implied: 1 - clear - rain

	val targetWidth: Int = 1280,
	val targetHeight: Int = 720,
	/** Pixels cropped from the left/right before saving (applied after scale). */
	val cropX: Int = 384,
	/** Pixels cropped from the top/bottom before saving (applied after scale). */
	val cropY: Int = 216,
	/** Final image width after crop. */
	val cropWidth: Int = 512,
	/** Final image height after crop. */
	val cropHeight: Int = 288,
	/** Output image format: "jpg" or "png". */
	val imageFormat: String = "jpg",
	/** JPEG quality 0.0–1.0 (only used when imageFormat = "jpg"). */
	val jpegQuality: Float = 0.90f,

	/** Randomly make ~50% of ageable mobs babies. */
	val babies: Boolean = false,
	/** Roll a random color/type/size/biome variant per mob where available (see VARIANTS.md). */
	val variants: Boolean = false,
	/** Equip random armor / held items on mobs. */
	val equipmentAndPoses: Boolean = false,
	/** Spawn multiple mobs simultaneously per frame (uses AABB boxes). */
	val multipleMobsPerFrame: Boolean = false,
	/** How many extra mobs to spawn when multipleMobsPerFrame is on (1 = pairs). */
	val extraMobsCount: Int = 2,
	/** Add small per-shot camera jitter and wider time-of-day spread. */
	val cameraJitterAndLighting: Boolean = false,
	/** Max yaw/pitch jitter in degrees when cameraJitterAndLighting is on. */
	val cameraJitterDegrees: Float = 4f,
	/** Max random look offset in degrees applied unconditionally before each shot
	 *  so the mob is not always perfectly centred in the frame. */
	val lookOffsetDegrees: Float = 10f,
	/** Fraction of extra "negative" frames (no mob visible) inserted for false-positive suppression. */
	val negativeFraction: Float = 0.05f,
)

object ConfigHolder {
	private val gson = GsonBuilder().setPrettyPrinting().create()

	@Volatile
	var config: YoloConfig = YoloConfig()
		private set

	private fun file(mc: Minecraft) = File(mc.gameDirectory, "config/yologen.json")

	fun load(mc: Minecraft) {
		val f = file(mc)
		if (!f.exists()) {
			save(mc)
			return
		}
		try {
			config = gson.fromJson(f.readText(), YoloConfig::class.java) ?: YoloConfig()
		} catch (_: Exception) {
			config = YoloConfig()
			save(mc) // overwrite corrupt file with defaults
		}
	}

	/** Reloads config from disk and returns the new instance. */
	fun reload(mc: Minecraft): YoloConfig {
		load(mc)
		return config
	}

	private fun sortedJsonObject(element: JsonElement): JsonElement {
		if (element !is JsonObject) return element
		val sorted = JsonObject()
		element.entrySet().sortedBy { it.key }.forEach { (k, v) -> sorted.add(k, sortedJsonObject(v)) }
		return sorted
	}

	private fun save(mc: Minecraft) {
		val f = file(mc)
		f.parentFile?.mkdirs()
		f.writeText(gson.toJson(sortedJsonObject(gson.toJsonTree(config))))
	}
}
