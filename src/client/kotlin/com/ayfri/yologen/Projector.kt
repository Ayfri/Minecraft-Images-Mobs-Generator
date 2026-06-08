package com.ayfri.yologen

import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import org.joml.Vector4f

private fun projectCorner(
    wx: Float, wy: Float, wz: Float,
    camX: Float, camY: Float, camZ: Float,
    camera: CameraRenderState,
    screenW: Int, screenH: Int,
): Pair<Float, Float>? {
    val clip = Vector4f(wx - camX, wy - camY, wz - camZ, 1f)
    clip.mul(camera.viewRotationMatrix)
    clip.mul(camera.projectionMatrix)
    if (clip.w <= 0f) return null
    val ndcX = clip.x / clip.w
    val ndcY = clip.y / clip.w
    return (ndcX + 1f) / 2f * screenW to (1f - ndcY) / 2f * screenH
}

fun EntityRenderState.toYoloBox(camera: CameraRenderState, screenW: Int, screenH: Int): YoloBox? {
    val classId = YOLO_CLASS_MAP[entityType] ?: return null
    if (isInvisible) return null

    val ex = x.toFloat()
    val ey = y.toFloat()
    val ez = z.toFloat()
    val hw = boundingBoxWidth / 2f
    val eh = boundingBoxHeight

    val camX = camera.pos.x.toFloat()
    val camY = camera.pos.y.toFloat()
    val camZ = camera.pos.z.toFloat()

    var minSX = Float.MAX_VALUE
    var minSY = Float.MAX_VALUE
    var maxSX = -Float.MAX_VALUE
    var maxSY = -Float.MAX_VALUE
    var hits = 0

    for (cx in floatArrayOf(ex - hw, ex + hw)) {
        for (cy in floatArrayOf(ey, ey + eh)) {
            for (cz in floatArrayOf(ez - hw, ez + hw)) {
                val (sx, sy) = projectCorner(cx, cy, cz, camX, camY, camZ, camera, screenW, screenH) ?: continue
                hits++
                if (sx < minSX) minSX = sx
                if (sy < minSY) minSY = sy
                if (sx > maxSX) maxSX = sx
                if (sy > maxSY) maxSY = sy
            }
        }
    }

    if (hits == 0) return null

    minSX = minSX.coerceIn(0f, screenW.toFloat())
    minSY = minSY.coerceIn(0f, screenH.toFloat())
    maxSX = maxSX.coerceIn(0f, screenW.toFloat())
    maxSY = maxSY.coerceIn(0f, screenH.toFloat())

    val bw = maxSX - minSX
    val bh = maxSY - minSY
    if (bw < 5f || bh < 5f) return null

    return YoloBox(
        classId,
        (minSX + bw / 2f) / screenW,
        (minSY + bh / 2f) / screenH,
        bw / screenW,
        bh / screenH,
    )
}
