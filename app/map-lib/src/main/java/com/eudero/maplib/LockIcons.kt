package com.eudero.maplib

import android.graphics.*

/** 깔끔한 자물쇠 아이콘(잠김/열림). 짙은 단색, 몸통은 꽉 찬 둥근 사각형. */
object LockIcons {
    fun draw(size: Int, locked: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        val s = size / 100f
        val ink = 0xFF14171C.toInt()
        val shackle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 9f * s; strokeCap = Paint.Cap.ROUND; color = ink }
        val p = Path()
        if (locked) { p.moveTo(34 * s, 46 * s); p.lineTo(34 * s, 33 * s); p.arcTo(RectF(34 * s, 15 * s, 66 * s, 51 * s), 180f, 180f); p.lineTo(66 * s, 46 * s) }
        else { p.moveTo(34 * s, 46 * s); p.lineTo(34 * s, 33 * s); p.arcTo(RectF(34 * s, 15 * s, 66 * s, 51 * s), 180f, 180f); p.lineTo(66 * s, 30 * s) }
        if (!locked) { c.save(); c.translate(14 * s, -2 * s); c.rotate(14f, 50 * s, 46 * s) }
        c.drawPath(p, shackle)
        if (!locked) c.restore()
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
        c.drawRoundRect(RectF(26 * s, 46 * s, 74 * s, 86 * s), 9 * s, 9 * s, body)
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = EudMapView.ACCENT }
        c.drawCircle(50 * s, 62 * s, 5f * s, hole); c.drawRoundRect(RectF(47.5f * s, 63 * s, 52.5f * s, 74 * s), 2.5f * s, 2.5f * s, hole)
        return bmp
    }
}
