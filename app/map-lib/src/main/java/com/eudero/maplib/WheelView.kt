package com.eudero.maplib

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/** 롤(LoL) 핑 휠 같은 원형 메뉴. 손가락을 그 방향으로 끌어 놓으면 선택. */
class WheelView(ctx: Context) : View(ctx) {
    class Item(val icon: String, val label: String, val color: Int, var bmp: android.graphics.Bitmap? = null)

    var items: List<Item> = emptyList()
    var selected = -1; private set
    private var cx = 0f; private var cy = 0f
    private var active = false
    private val d = resources.displayMetrics.density
    private val outer = 82f * d
    private var startX = 0f; private var startY = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d; color = 0xA6FFFFFF.toInt() }
    private val bounds = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = MapFonts.bold }

    fun open(x: Float, y: Float, w: Int, h: Int) {
        cx = x.coerceIn(outer + 8 * d, (w - outer - 8 * d).coerceAtLeast(outer + 8 * d))
        cy = y.coerceIn(outer + 8 * d, (h - outer - 8 * d).coerceAtLeast(outer + 8 * d))
        startX = x; startY = y
        bounds.set(cx - outer, cy - outer, cx + outer, cy + outer)
        animate().cancel()
        selected = -1; active = true; visibility = VISIBLE; invalidate()
        pivotX = cx; pivotY = cy; scaleX = 0.7f; scaleY = 0.7f; alpha = 0f
        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).start()
    }
    fun close() { animate().cancel(); active = false; selected = -1; visibility = GONE }

    /** @return 선택이 바뀌었으면 true */
    fun move(x: Float, y: Float): Boolean {
        val dx = x - cx
        val distance = hypot(dx, y - cy)
        val s = when {
            hypot(x - startX, y - startY) < 10f * d || abs(dx) < 5f * d || distance > outer + 12f * d -> -1
            dx < 0f -> 0
            else -> 1
        }
        if (s != selected) { selected = s; invalidate(); return true }
        return false
    }

    override fun onDraw(c: Canvas) {
        if (!active) return
        fill.color = 0x99181C15.toInt()
        c.drawCircle(cx, cy, outer, fill)
        items.getOrNull(selected)?.let { item ->
            fill.color = (item.color and 0x00FFFFFF) or 0x66000000
            c.drawArc(bounds, if (selected == 0) 90f else -90f, 180f, true, fill)
        }
        c.drawCircle(cx, cy, outer, ring)
        c.drawLine(cx, cy - outer, cx, cy + outer, ring)
        items.forEachIndexed { i, it ->
            val x = cx + (if (i == 0) -0.48f else 0.48f) * outer
            val y = cy
            text.color = Color.WHITE
            val bm = it.bmp
            if (bm != null) c.drawBitmap(bm, null, RectF(x - 17f * d, y - 20f * d, x + 17f * d, y + 14f * d), null) else { text.textSize = 22f * d; c.drawText(it.icon, x, y + 2f * d, text) }
            text.textSize = 13f * d; c.drawText(it.label, x, y + 18f * d, text)
        }
    }
}
