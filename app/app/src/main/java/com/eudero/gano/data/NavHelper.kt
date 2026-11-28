package com.eudero.gano.data

import com.eudero.maplib.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt

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
    private val cumulative = DoubleArray(pts.size).also { values ->
        for (i in 1 until pts.size) values[i] = values[i - 1] + pts[i - 1].distanceToAsDouble(pts[i])
    }
    class Step(val text: String, val bearing: Float)

    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val dy = b.latitude - a.latitude; val dx = (b.longitude - a.longitude) * cos(Math.toRadians(a.latitude))
        return (Math.toDegrees(atan2(dx, dy)) + 360) % 360
    }

    fun step(me: GeoPoint, allowArrival: Boolean = true): Step {
        if (pts.size < 2) return Step("안내할 경로가 없어요", 0f)
        val scale = cos(Math.toRadians(me.latitude))
        var idx = 0
        var closest = project(me, pts[0], pts[1], scale)
        for (i in 1 until pts.lastIndex) {
            val snap = project(me, pts[i], pts[i + 1], scale)
            if (snap.offsetM < closest.offsetM) { idx = i; closest = snap }
        }
        val h = head[idx]
        var nextTurnM = closest.point.distanceToAsDouble(pts[idx + 1])
        val remain = nextTurnM + cumulative.last() - cumulative[idx + 1]
        if (remain < 25 && me.distanceToAsDouble(pts.last()) < 25) {
            val distance = me.distanceToAsDouble(pts.last()).roundToInt()
            return if (allowArrival) Step("도착했어요! 수고했어요 🎉", h.toFloat())
                else Step("합류점까지 ${distance}m", bearing(me, pts.last()).toFloat())
        }
        // 다음 큰 방향 전환
        var j = idx + 1
        while (j < head.size) {
            var diff = head[j] - head[j - 1]; while (diff > 180) diff -= 360; while (diff < -180) diff += 360
            if (abs(diff) > 38) {
                val dist = nextTurnM.roundToInt()
                return Step("${dist}m 앞에서 ${if (diff > 0) "우회전" else "좌회전"}", h.toFloat())
            }
            nextTurnM += cumulative[j + 1] - cumulative[j]
            j++
        }
        val destination = if (allowArrival) "종점" else "합류점"
        return Step("직진하세요 · ${destination}까지 ${remain.toInt()}m", h.toFloat())
    }
}
