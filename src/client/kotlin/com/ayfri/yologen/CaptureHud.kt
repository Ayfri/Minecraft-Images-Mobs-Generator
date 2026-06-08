package com.ayfri.yologen

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier

private const val CLEAR_BG = 0xFF16351C.toInt()
private const val CLEAR_FG = 0xFF55E36D.toInt()
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
	textRight(font, value, x + w - 6, y + 3, TEXT_MAIN)
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
		val panelW = 430
		val x = g.guiWidth() / 2 - panelW / 2
		val y0 = 5
		val barX = x + 10
		val barW = panelW - 20
		val barH = 12
		val lh = font.lineHeight
		val isCapturing = AutoCapture.phase == AutoCapture.Phase.CAPTURING
		val thunderShots = AutoCapture.SHOTS_PER_MOB - AutoCapture.SHOTS_CLEAR - AutoCapture.SHOTS_RAIN
		val panelH = if (AutoCapture.terrainWaitTick > 0) 106 else 92

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
		g.textRight(font, "total ${AutoCapture.totalShots} frames", x + panelW - 10, y, TEXT_DIM)
		y += lh + 5

		val mobText = if (AutoCapture.currentMobName.isEmpty())
			"waiting for tagged NoAI mob spawn"
		else
			"mob ${AutoCapture.mobIndex}/${MOB_TYPES.size}: ${AutoCapture.currentMobName}"
		g.text(font, mobText, x + 10, y, TEXT_MAIN)
		val hour = (AutoCapture.currentTime / 1000L + 6L) % 24L
		val clock = "%02d:00".format(hour)
		g.textRight(font, clock, x + panelW - 10, y, TEXT_DIM)
		y += lh + 5

		val clearW = barW * AutoCapture.SHOTS_CLEAR / AutoCapture.SHOTS_PER_MOB
		val rainW = barW * AutoCapture.SHOTS_RAIN / AutoCapture.SHOTS_PER_MOB
		val thunderW = barW - clearW - rainW
		g.fill(barX, y, barX + clearW, y + barH, CLEAR_BG)
		g.fill(barX + clearW, y, barX + clearW + rainW, y + barH, RAIN_BG)
		g.fill(barX + clearW + rainW, y, barX + barW, y + barH, THUNDER_BG)

		val filled = if (isCapturing) (barW * AutoCapture.shotCount / AutoCapture.SHOTS_PER_MOB) else
			(barW * AutoCapture.setupTick / AutoCapture.SETUP_WAIT_TICKS)
		if (isCapturing) {
			val cf = filled.coerceAtMost(clearW)
			val rf = (filled - clearW).coerceIn(0, rainW)
			val tf = (filled - clearW - rainW).coerceIn(0, thunderW)
			if (cf > 0) g.fill(barX, y, barX + cf, y + barH, CLEAR_FG)
			if (rf > 0) g.fill(barX + clearW, y, barX + clearW + rf, y + barH, RAIN_FG)
			if (tf > 0) g.fill(barX + clearW + rainW, y, barX + clearW + rainW + tf, y + barH, THUNDER_FG)
		} else {
			g.fill(barX, y, barX + filled.coerceIn(0, barW), y + barH, WARN)
		}

		val batchPx = barW * AutoCapture.RELOCATE_EVERY / AutoCapture.SHOTS_PER_MOB
		var divX = barX + batchPx
		while (divX < barX + barW) {
			g.fill(divX, y - 1, divX + 1, y + barH + 1, 0x77FFFFFF)
			divX += batchPx
		}
		g.fill(barX + clearW, y - 1, barX + clearW + 1, y + barH + 1, 0xDDFFFFFF.toInt())
		g.fill(barX + clearW + rainW, y - 1, barX + clearW + rainW + 1, y + barH + 1, 0xDDFFFFFF.toInt())
		val cursorX = barX + filled.coerceIn(0, barW)
		g.fill(cursorX - 1, y - 3, cursorX + 2, y + barH + 3, 0xFFFFFFFF.toInt())
		y += barH + 5

		g.label(font, "clear ${AutoCapture.SHOTS_CLEAR}", x + 10, y, CLEAR_FG)
		g.label(font, "rain ${AutoCapture.SHOTS_RAIN}", x + 135, y, RAIN_FG)
		g.label(font, "thunder $thunderShots", x + 250, y, THUNDER_FG)
		g.textRight(font, "ticks mark relocation batches", x + panelW - 10, y, TEXT_DIM)
		y += lh + 6

		val shotLabel = if (isCapturing) "${AutoCapture.shotCount}/${AutoCapture.SHOTS_PER_MOB}" else "${AutoCapture.setupTick}/${AutoCapture.SETUP_WAIT_TICKS}"
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
		y += 18

		g.text(font, "relocation: $preloadLabel  | every ${AutoCapture.RELOCATE_EVERY} shots | radius ±${AutoCapture.BIOME_SCAN_RADIUS}", x + 10, y, TEXT_DIM)
		if (AutoCapture.terrainWaitTick > 0) {
			y += lh + 2
			g.text(font, "waiting for client terrain before snapping mob to surface", x + 10, y, WARN)
		}
	})
}
