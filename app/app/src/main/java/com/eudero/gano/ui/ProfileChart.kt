package com.eudero.gano.ui

import android.content.Context
import android.graphics.*
import android.view.View
import com.eudero.gano.data.CourseProfile
import com.eudero.maplib.facilityIcon

/** 길을 일자로 편 오르막 그래프. 단색(형광 연두) 선과 채움. 가로·세로 비율을 실제 길에 가깝게 그려서 진짜 오르막 각도처럼 보인다. */
class ProfileChart(ctx: Context) : View(ctx) {
    var profile: CourseProfile? = null
        set(v) { field = v; invalidate() }
    private val pts get() = profile?.points.orEmpty()
    var expandElevationRange = false
        set(value) { field = value; invalidate() }
    private val d = resources.displayMetrics.density
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = 3.4f * d; color = 0xFFB9FA03.toInt() }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA0A9.toInt(); textSize = 11f * d }
    private val base = Paint().apply { color = 0x33FFFFFF; strokeWidth = 1f * d }

    private val red = Paint(lineP).apply { color = 0xFFFF525B.toInt() }
    private val crossingIcon by lazy { facilityIcon(d, false) }
    override fun onDraw(c: Canvas) {
        if (pts.size < 3) return
        val left = 8 * d; val right = width - 8 * d; val top = 10 * d; val bottom = height - 22 * d
        val w = right - left; val h = bottom - top
        val total = pts.last().first.toFloat().coerceAtLeast(1f)
        val emin = pts.minOf { it.second }; val emax = pts.maxOf { it.second }
        // 실제 비율: 가로 1m 와 세로 1m 가 같은 길이. 너무 평평하면 최소 높이까지만 살짝 과장, 너무 크면 화면에 맞춰 줄임
        val pxPerM = w / total
        var vScale = pxPerM
        val realH = (emax - emin) * vScale
        if (realH < 26 * d) vScale = if (emax > emin) 26 * d / (emax - emin) else 0f
        if ((emax - emin) * vScale > h) vScale = h / (emax - emin)
        if (expandElevationRange && emax > emin) vScale = (h - 6 * d).coerceAtLeast(0f) / (emax - emin)
        fun x(dist: Int) = left + w * dist / total
        fun y(e: Float) = bottom - (e - emin) * vScale
        val path = Path(); val fill = Path()
        fill.moveTo(x(pts.first().first), bottom)
        pts.forEachIndexed { i, p -> val px = x(p.first); val py = y(p.second) - 6 * d; if (i == 0) path.moveTo(px, py) else path.lineTo(px, py); fill.lineTo(px, py) }
        fill.lineTo(x(pts.last().first), bottom); fill.close()
        fillP.shader = LinearGradient(0f, top, 0f, bottom, 0x66B9FA03, 0x08B9FA03, Shader.TileMode.CLAMP)
        c.drawPath(fill, fillP); c.drawPath(path, lineP)
        for (i in 1 until pts.size) {
            val (da, ea) = pts[i - 1]; val (db, eb) = pts[i]
            if (CourseProfile.isSteep(db - da, eb - ea)) c.drawLine(x(da), y(ea) - 6*d, x(db), y(eb) - 6*d, red)
        }
        c.drawLine(left, bottom, right, bottom, base)
        profile?.markers?.forEach { mark ->
            if (mark.dot.signal) return@forEach
            val pos = x(mark.distance).coerceIn(left + 9*d, right - 9*d)
            val icon = crossingIcon
            c.drawBitmap(icon, null, RectF(pos-7*d, bottom-1*d, pos+7*d, bottom+13*d), null)
        }
        txt.textAlign = Paint.Align.LEFT; c.drawText("0", left, height - 6 * d, txt)
        txt.textAlign = Paint.Align.RIGHT; c.drawText("%.1f km".format(total / 1000f), right, height - 6 * d, txt)
    }
}
