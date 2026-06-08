package com.ayfri.yologen

data class YoloBox(val classId: Int, val x: Float, val y: Float, val w: Float, val h: Float) {
	fun toTxtLine() = "$classId $x $y $w $h"
}
