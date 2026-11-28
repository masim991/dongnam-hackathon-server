package com.eudero.gano.data

import com.eudero.maplib.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

/** 안내 경로에서 다음 안내(남은 거리, 방향)를 계산한다. */
class NavHelper(path: List<GeoPoint>) {
    private val pts = ArrayList<GeoPoint>()
    private val head = ArrayList<Double>()
    init {   // 약 20m 간격으로 다시 찍기
        if (path.isNotEmpty()) { pts.add(path[0]); var acc = 0.0
            for (i in 1 until path.size) { acc += path[i - 1].distanceToAsDouble(path[i]); if (acc >= 20.0) { pts.add(path[i]); acc = 0.0 } }
            if (pts.last() != path.last()) pts.add(path.last()) }
        for (i in 0 until pts.size - 1) head.add(bearing(pts[i], pts[i + 1]))
    }
    class Step(val text: String, val bearing: Float)

    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val dy = b.latitude - a.latitude; val dx = (b.longitude - a.longitude) * cos(Math.toRadians(a.latitude))
        return (Math.toDegrees(atan2(dx, dy)) + 360) % 360
    }

    fun step(me: GeoPoint, allowArrival: Boolean = true): Step {
        if (pts.size < 2) return Step("안내할 경로가 없어요", 0f)
        var idx = 0; var bd = Double.MAX_VALUE
        pts.forEachIndexed { i, p -> val d = me.distanceToAsDouble(p); if (d < bd) { bd = d; idx = i } }
        val h = head.getOrElse(minOf(idx, head.size - 1)) { 0.0 }
        var remain = 0.0; for (i in idx until pts.size - 1) remain += pts[i].distanceToAsDouble(pts[i + 1])
        if (remain < 25) {
            val distance = me.distanceToAsDouble(pts.last()).toInt()
            return if (allowArrival) Step("도착했어요! 수고했어요 🎉", h.toFloat())
                else Step("합류점까지 ${distance}m", bearing(me, pts.last()).toFloat())
        }
        // 다음 큰 방향 전환
        var j = idx + 1
        while (j < head.size) {
            var diff = head[j] - head[j - 1]; while (diff > 180) diff -= 360; while (diff < -180) diff += 360
            if (abs(diff) > 38) {
                val dist = (j - idx) * 20
                return Step("${dist}m 앞에서 ${if (diff > 0) "우회전" else "좌회전"}", h.toFloat())
            }
            j++
        }
        val destination = if (allowArrival) "종점" else "합류점"
        return Step("직진하세요 · ${destination}까지 ${remain.toInt()}m", h.toFloat())
    }
}
