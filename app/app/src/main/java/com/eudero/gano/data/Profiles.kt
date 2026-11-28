package com.eudero.gano.data

import android.content.Context
import org.json.JSONObject
import com.eudero.maplib.Dot
import com.eudero.maplib.Seg
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/** 코스 한 점: 시작부터 거리(m), 고도(m), 위치. (assets/profiles.json = 코스를 40m 간격으로 이은 순서 있는 선) */
class PP(val d: Int, val e: Float, val lat: Double, val lon: Double)

object Profiles {
    private var json: JSONObject? = null
    fun get(c: Context, code: String): List<PP> {
        val j = json ?: JSONObject(c.assets.open("profiles.json").bufferedReader().use { it.readText() }).also { json = it }
        val a = j.optJSONObject(code)?.optJSONArray("pts") ?: return emptyList()
        return (0 until a.length()).map { val p = a.getJSONArray(it); PP(p.getInt(0), p.getDouble(1).toFloat(), p.optDouble(2, 0.0), p.optDouble(3, 0.0)) }
    }
}

/** 거리 축은 선택한 방향의 출발점을 0으로 맞춘다. 경사율은 40m 단위 고도 표본의 추정치. */
class CourseProfile(samples: List<PP>, facilities: List<Dot>) {
    class Crossing(val distance: Int, val dot: Dot)
    val points = samples.map { abs(it.d - samples.first().d) to it.e }
    val steep = samples.zipWithNext().mapNotNull { (a, b) ->
        val distance = abs(b.d - a.d)
        val rise = abs(b.e - a.e)
        if (!isSteep(distance, rise)) null else Seg(a.lat, a.lon, b.lat, b.lon, (100 * rise / distance).roundToInt())
    }
    val along = facilities.mapNotNull { dot ->
        val mx = 111_320 * cos(Math.toRadians(dot.lat))
        var best = 20.0 * 20.0
        var at: Int? = null
        for (i in 1 until samples.size) {
            val a = samples[i-1]; val b = samples[i]
            val ax = (a.lon - dot.lon) * mx; val ay = (a.lat - dot.lat) * 111_320
            val dx = (b.lon - a.lon) * mx; val dy = (b.lat - a.lat) * 111_320
            val span = dx * dx + dy * dy
            if (span == 0.0) continue
            val t = (-(ax * dx + ay * dy) / span).coerceIn(0.0, 1.0)
            val x = ax + t * dx; val y = ay + t * dy
            val sq = x*x + y*y
            if (sq <= best) {
                best = sq
                at = (points[i-1].first + t * (points[i].first - points[i-1].first)).roundToInt()
            }
        }
        at?.let { Crossing(it, dot) }
    }.distinctBy { Triple(it.dot.lat, it.dot.lon, it.dot.signal) }.sortedBy { it.distance }
    val summary = crossingSummary(along.map { it.dot })
    // 같은 교차로의 표식이 겹치지 않게 하고, 두 종류가 모두 있으면 각각 먼저 하나씩 고른다.
    val markers = buildList<Crossing> {
        val gap = maxOf(35, (points.lastOrNull()?.first ?: 0) / 9)
        val firstSignal = along.firstOrNull { it.dot.signal }
        fun addSpaced(candidate: Crossing) {
            if (size < 3 && none { abs(it.distance - candidate.distance) < gap }) add(candidate)
        }
        firstSignal?.let(::addSpaced)
        along.filter { !it.dot.signal }.forEach(::addSpaced)
        along.filter { it.dot.signal }.forEach(::addSpaced)
    }

    companion object {
        fun crossingSummary(dots: List<Dot>) = "횡단보도 ${dots.count { !it.signal }}곳"
        const val STEEP_PERCENT = 10
        fun isSteep(distance: Int, rise: Float) = distance > 0 && 100f * abs(rise) >= STEEP_PERCENT * distance
    }
}
