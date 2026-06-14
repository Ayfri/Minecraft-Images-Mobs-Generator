package com.ayfri.yologen

import com.ayfri.yologen.config.ConfigHolder
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier

private const val CLEAR_BG = 0xFF16351C.toInt()
private const val CLEAR_FG = 0xFF55E36D.toInt()
private const val GLOBAL_BAR_BG = 0xFF0D1E35.toInt()
private const val GLOBAL_BAR_FG = 0xFF5AA7FF.toInt()
private const val PANEL_BG = 0xD80A0D12.toInt()
private const val PANEL_BORDER = 0xFF5AA7FF.toInt()
private const val RAIN_BG = 0xFF142C4D.toInt()
private const val RAIN_FG = 0xFF4FA3FF.toInt()
private const val TEXT_DIM = 0xFF9AA4B2.toInt()
private const val TEXT_MAIN = 0xFFFFFFFF.toInt()
private const val THUNDER_BG = 0xFF351747.toInt()
private const val THUNDER_FG = 0xFFD35BFF.toInt()
private const val WARN = 0xFFFFC857.toInt()

private fun GuiGraphicsExtractor.textRight(font: Font, text: String, rightX: Int, y: Int, color: Int = TEXT_MAIN) {
	text(font, text, rightX - font.width(text), y, color)
}

private fun GuiGraphicsExtractor.label(font: Font, text: String, x: Int, y: Int, color: Int) {
	fill(x, y + 1, x + 4, y + font.lineHeight - 1, color)
	text(font, text, x + 8, y, TEXT_MAIN)
}

private fun GuiGraphicsExtractor.stat(font: Font, label: String, value: String, x: Int, y: Int, w: Int, color: Int) {
	fill(x, y, x + w, y + 15, 0xAA151A22.toInt())
	fill(x, y, x + 2, y + 15, color)
	text(font, label, x + 6, y + 3, TEXT_DIM)
	textRight(font, value, x + w - 6, y + 3)
}

private fun weatherPhaseColor(phase: WeatherPhase) = when (phase) {
	WeatherPhase.CLEAR -> CLEAR_FG
	WeatherPhase.RAIN -> RAIN_FG
	WeatherPhase.THUNDER -> THUNDER_FG
}

private fun weatherPhaseBg(phase: WeatherPhase) = when (phase) {
	WeatherPhase.CLEAR -> CLEAR_BG
	WeatherPhase.RAIN -> RAIN_BG
	WeatherPhase.THUNDER -> THUNDER_BG
}

private fun weatherColor(weather: String) = when (weather) {
	"rain" -> RAIN_FG
	"thunder" -> THUNDER_FG
	else -> CLEAR_FG
}

fun registerHud() {
	HudElementRegistry.addLast(Identifier.parse("yologen:progress"), HudElement { g, _ ->
		if (!AutoCapture.running || AutoCapture.phase == AutoCapture.Phase.IDLE) return@HudElement

		val mc = Minecraft.getInstance()
		val font = mc.font
		val cfg = ConfigHolder.config
		val panelW = 430
		val x = g.guiWidth() / 2 - panelW / 2
		val y0 = 5
		val barX = x + 10
		val barW = panelW - 20
		val barH = 12
		val lh = font.lineHeight
		val isCapturing = AutoCapture.phase == AutoCapture.Phase.CAPTURING
		val panelH = if (AutoCapture.terrainWaitTick > 0) 152 else 138

		// ETA: real wall-clock elapsed / fraction done → remaining real time
		val shotsPerMob = AutoCapture.SHOTS_PER_MOB
		val totalShots = MOB_TYPES.size * shotsPerMob
		val doneShots = AutoCapture.completedCount * shotsPerMob + AutoCapture.shotCount
		val elapsedMs = System.currentTimeMillis() - AutoCapture.startTimeMs
		val captureElapsedMs =
			if (AutoCapture.firstShotMs > 0L) System.currentTimeMillis() - AutoCapture.firstShotMs else 0L
		val shotsPerSec =
			if (captureElapsedMs > 1000) "%.1f sps".format(AutoCapture.totalShots * 1000.0 / captureElapsedMs) else "…"
		val etaText = if (doneShots > 0 && captureElapsedMs > 0) {
			val msLeft = captureElapsedMs * (totalShots - doneShots) / doneShots
			val secsLeft = (msLeft / 1000).toInt()
			"%d:%02d".format(secsLeft / 60, secsLeft % 60)
		} else "…"

		g.fill(x, y0, x + panelW, y0 + panelH, PANEL_BG)
		g.fill(x, y0, x + panelW, y0 + 1, PANEL_BORDER)
		g.fill(x, y0 + panelH - 1, x + panelW, y0 + panelH, 0xAA5AA7FF.toInt())
		g.fill(x, y0, x + 1, y0 + panelH, PANEL_BORDER)
		g.fill(x + panelW - 1, y0, x + panelW, y0 + panelH, 0x885AA7FF.toInt())

		var y = y0 + 7
		val phaseLabel = if (isCapturing) "CAPTURING DATASET" else "PREPARING SCENE"
		val phaseColor = if (isCapturing) CLEAR_FG else WARN
		g.text(font, "YoloGen", x + 10, y, TEXT_MAIN)
		g.fill(x + 62, y + 1, x + 66, y + lh - 1, phaseColor)
		g.text(font, phaseLabel, x + 70, y, phaseColor)
		if (isCapturing) {
			g.textRight(font, "$shotsPerSec  ETA $etaText", x + panelW - 10, y, PANEL_BORDER)
		}
		y += lh + 5

		val mobText = if (AutoCapture.currentMobName.isEmpty())
			"waiting for tagged NoAI mob spawn"
		else
			"mob ${AutoCapture.completedCount + 1}/${MOB_TYPES.size}: ${AutoCapture.currentMobName}"
		g.text(font, mobText, x + 10, y, TEXT_MAIN)
		val hour = (AutoCapture.currentTime / 1000L + 6L) % 24L
		g.textRight(font, "%02d:00".format(hour), x + panelW - 10, y, TEXT_DIM)
		y += lh + 5

		// Per-mob weather bar (background segments)
		var segX = barX
		for (phase in WeatherPhase.entries) {
			val segW = (barW * phase.fraction(cfg)).toInt().let {
				if (phase == WeatherPhase.entries.last()) barX + barW - segX else it
			}
			g.fill(segX, y, segX + segW, y + barH, weatherPhaseBg(phase))
			segX += segW
		}

		// Per-mob weather bar (filled portion)
		val shotFilled = if (isCapturing) barW * AutoCapture.shotCount / cfg.shotsPerMob else
			(barW * AutoCapture.setupTick / AutoCapture.SETUP_WAIT_TICKS).coerceAtMost(barW)
		if (isCapturing) {
			var filledLeft = shotFilled
			var drawX = barX
			for (phase in WeatherPhase.entries) {
				val segW = (barW * phase.fraction(cfg)).toInt().let {
					if (phase == WeatherPhase.entries.last()) barX + barW - drawX else it
				}
				val fill = filledLeft.coerceIn(0, segW)
				if (fill > 0) g.fill(drawX, y, drawX + fill, y + barH, weatherPhaseColor(phase))
				filledLeft -= segW
				drawX += segW
				if (filledLeft <= 0) break
			}
		} else {
			g.fill(barX, y, barX + shotFilled.coerceIn(0, barW), y + barH, WARN)
		}

		// Weather transition markers + cursor
		var markerX = barX
		for (phase in WeatherPhase.entries.dropLast(1)) {
			markerX += (barW * phase.fraction(cfg)).toInt()
			g.fill(markerX, y - 1, markerX + 1, y + barH + 1, 0xDDFFFFFF.toInt())
		}
		val cursorX = barX + shotFilled.coerceIn(0, barW)
		g.fill(cursorX - 1, y - 3, cursorX + 2, y + barH + 3, 0xFFFFFFFF.toInt())
		y += barH + 5

		// Weather phase labels with percentage
		val entryW = barW / WeatherPhase.entries.size
		WeatherPhase.entries.forEachIndexed { i, phase ->
			g.label(font, "${phase.label} ${phase.pct(cfg)}%", barX + i * entryW, y, weatherPhaseColor(phase))
		}
		y += lh + 6

		val setupStuck = !isCapturing && AutoCapture.setupTick > AutoCapture.SETUP_WAIT_TICKS
		val setupBlockReason = when {
			setupStuck && !AutoCapture.mobSpawned -> "waiting spawn"
			setupStuck && !AutoCapture.poolBuildDone -> "building pool"
			else -> null
		}
		val shotLabel = when {
			isCapturing -> "${AutoCapture.shotCount}/${cfg.shotsPerMob}"
			setupBlockReason != null -> setupBlockReason
			else -> "${AutoCapture.setupTick}/${AutoCapture.SETUP_WAIT_TICKS}"
		}
		val weatherLabel = if (isCapturing) AutoCapture.currentWeather else "setup"
		val preloadLabel = when {
			AutoCapture.terrainWaitTick > 0 -> "terrain ${AutoCapture.terrainWaitTick}t"
			AutoCapture.hasNextRelocation -> "next ready"
			else -> "mapping"
		}
		val statW = (panelW - 32) / 3
		g.stat(font, "shot/setup", shotLabel, x + 10, y, statW, phaseColor)
		g.stat(font, "weather", weatherLabel, x + 16 + statW, y, statW, weatherColor(AutoCapture.currentWeather))
		g.stat(font, "biomes", "${AutoCapture.relocationPoolSize} mapped", x + 22 + statW * 2, y, statW, PANEL_BORDER)
		y += 21

		// Global progress bar
		val totalShotsAll = MOB_TYPES.size * cfg.shotsPerMob
		val shotsDoneGlobal = (AutoCapture.completedCount * cfg.shotsPerMob + AutoCapture.shotCount)
			.coerceAtMost(totalShotsAll)
		val globalFilled = barW * shotsDoneGlobal / totalShotsAll
		g.fill(barX, y, barX + barW, y + barH, GLOBAL_BAR_BG)
		if (globalFilled > 0) g.fill(barX, y, barX + globalFilled, y + barH, GLOBAL_BAR_FG)
		val globalCursor = barX + globalFilled
		g.fill(globalCursor - 1, y - 2, globalCursor + 2, y + barH + 2, 0xFFFFFFFF.toInt())
		y += barH + 4

		val globalPct = if (totalShotsAll > 0) shotsDoneGlobal * 100 / totalShotsAll else 0
		g.text(
			font,
			"global: $shotsDoneGlobal/$totalShotsAll  ($globalPct%)  total captured: ${AutoCapture.totalShots}",
			x + 10,
			y,
			TEXT_DIM
		)
		y += lh + 2

		g.text(
			font,
			"relocation: $preloadLabel  | every ${cfg.relocateEvery} shots | radius ±${cfg.biomeSearchRadius}",
			x + 10,
			y,
			TEXT_DIM
		)
		if (AutoCapture.terrainWaitTick > 0) {
			y += lh + 2
			g.text(font, "waiting for client terrain before snapping mob to surface", x + 10, y, WARN)
		}
	})
}
