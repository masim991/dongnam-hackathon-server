package com.eudero.maplib

import android.location.Location
import android.location.LocationManager
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * GPS 튐 방지 필터: ① 정확도 낮은 위치 버림 ② 말이 안 되는 속도로 튀는 점 무시 ③ 칼만 필터로 부드럽게.
 * 길에 붙이기(snap)는 선택: 경로가 주어지면 가까운 경로 위 점으로 살짝 당긴다.
 */
class LocationFilter(
    private val maxAccuracyM: Float = 35f,
    private val maxSpeedMs: Double = 11.0,      // 약 40km/h. 달리기 속도 한계
    private val processNoise: Double = 3.0      // m²/s : 클수록 실제 움직임을 빨리 따라감
) {
    private var inited = false
    private var lat = 0.0; private var lon = 0.0; private var variance = 0.0; private var tMs = 0L
    private var lastRawLat = 0.0; private var lastRawLon = 0.0; private var lastRawT = 0L
    private var rejected = 0
    var snapPath: List<Pair<Double, Double>>? = null   // (lat, lon) 목록
    var snapRadiusM = 22.0

    fun reset() { inited = false; rejected = 0 }

    private fun dist(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val k = 111320.0; return hypot((aLat - bLat) * k, (aLon - bLon) * k * cos(Math.toRadians(aLat)))
    }

    /** @return 쓸 만한 위치(부드럽게 보정됨) 또는 null(버림) */
    fun process(l: Location): Location? {
        val acc = if (l.hasAccuracy() && l.accuracy > 0f) l.accuracy else 10f   // 정확도 정보가 없으면(에뮬레이터 등) 10m로 본다
        if (acc > maxAccuracyM) return null
        val t = if (l.time > 0) l.time else System.currentTimeMillis()
        if (!inited) { start(l, t); return out(l, lat, lon) }
        val dt = max((t - lastRawT) / 1000.0, 0.001)
        if (t <= lastRawT) return null
        val d = dist(lastRawLat, lastRawLon, l.latitude, l.longitude)
        val speed = d / dt
        if (speed > maxSpeedMs && d > 15.0 && dt < 30.0) {          // 순간이동 같은 점 무시
            rejected++
            if (rejected >= 4) start(l, t)                            // 계속 같은 곳이면 진짜 이동으로 보고 다시 시작
            else return null
            return out(l, lat, lon)
        }
        rejected = 0
        variance += dt * processNoise
        val acc2 = (acc * acc).toDouble()
        val k = variance / (variance + acc2)
        lat += k * (l.latitude - lat); lon += k * (l.longitude - lon); variance *= (1 - k)
        tMs = t; lastRawLat = l.latitude; lastRawLon = l.longitude; lastRawT = t
        return out(l, lat, lon)
    }

    private fun start(l: Location, t: Long) {
        inited = true; lat = l.latitude; lon = l.longitude; variance = ((if (l.hasAccuracy() && l.accuracy > 0f) l.accuracy else 10f).let { it * it }).toDouble(); tMs = t
        lastRawLat = l.latitude; lastRawLon = l.longitude; lastRawT = t; rejected = 0
    }

    private fun out(src: Location, la: Double, lo: Double): Location {
        var a = la; var b = lo
        snapPath?.let { p -> snap(p, la, lo)?.let { a = it.first; b = it.second } }
        return Location(LocationManager.GPS_PROVIDER).apply {
            latitude = a; longitude = b; accuracy = if (src.hasAccuracy() && src.accuracy > 0f) src.accuracy else 10f; time = src.time; elapsedRealtimeNanos = src.elapsedRealtimeNanos
            if (src.hasSpeed()) speed = src.speed; if (src.hasBearing()) bearing = src.bearing
        }
    }

    /** 경로(선분)에서 가장 가까운 점. 반경 안일 때만 당긴다 */
    private fun snap(p: List<Pair<Double, Double>>, la: Double, lo: Double): Pair<Double, Double>? {
        var best = Double.MAX_VALUE; var bp: Pair<Double, Double>? = null
        val k = 111320.0; val kx = k * cos(Math.toRadians(la))
        for (i in 0 until p.size - 1) {
            val ax = (p[i].second - lo) * kx; val ay = (p[i].first - la) * k
            val bx = (p[i + 1].second - lo) * kx; val by = (p[i + 1].first - la) * k
            val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / l2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy; val d = hypot(px, py)
            if (d < best) { best = d; bp = (la + py / k) to (lo + px / kx) }
        }
        return if (best <= snapRadiusM) bp else null
    }
}
