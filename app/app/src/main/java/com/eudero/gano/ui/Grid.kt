package com.eudero.gano.ui

import android.graphics.*
import android.graphics.drawable.Drawable

/** 네모 격자 + 트랙 레인 느낌의 은은한 배경. */
class GridDrawable(private val d: Float, private val base: Int, private val radiusTop: Float = 0f, private val lane: Boolean = true) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = base }
    private val line = Paint().apply { color = 0x12FFFFFF; strokeWidth = 1f }
    private val laneP = Paint().apply { color = 0x22B9FA03; strokeWidth = 2f * d }
    override fun draw(c: Canvas) {
        val b = bounds; val step = 28f * d
        val clip = Path().apply { addRoundRect(RectF(b), floatArrayOf(radiusTop, radiusTop, radiusTop, radiusTop, 0f, 0f, 0f, 0f), Path.Direction.CW) }
        c.save(); c.clipPath(clip)
        c.drawRect(b, fill)
        var x = b.left + step; while (x < b.right) { c.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), line); x += step }
        var y = b.top + step; while (y < b.bottom) { c.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, line); y += step }
        if (lane) { val ly = b.bottom - 3f * d; c.drawLine(b.left.toFloat(), ly, b.right.toFloat(), ly, laneP) }
        c.restore()
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
