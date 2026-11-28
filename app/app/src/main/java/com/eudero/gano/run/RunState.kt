package com.eudero.gano.run

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** 러닝 진행 상태(싱글톤). 서비스가 쓰고 화면/위젯이 읽는다. */
object RunState {
    data class Pt(val lat: Double, val lon: Double, val t: Long)

    @Volatile var running = false
    @Volatile var startMs = 0L
    @Volatile private var startElapsedMs = 0L
    @Volatile var distanceM = 0.0
    @Volatile var steps: Long? = null
    @Volatile var hr = 0
    @Volatile var hrSource = ""
    // 목표: 0 자유, 1 거리(m), 2 시간(초)
    @Volatile var goalType = 3
    @Volatile var paceSecPerKm = 360   // 페이스 설정(공통), 기본 6'00"
    @Volatile var goalValue = 0.0
    @Volatile var phasesOn = true
    // 길 안내 계획 (시험 달리기와 기록 제목에 사용)
    @Volatile var simPath: List<com.eudero.maplib.GeoPoint>? = null
    @Volatile var selectedCourse = emptyList<com.eudero.maplib.GeoPoint>()
    @Volatile var pendingEntry: com.eudero.maplib.GeoPoint? = null
    @Volatile var courseName = ""
    @Volatile var sim = false
    @Volatile var simSpeed = 20.0        // 시연·시험 달리기 속도(m/초)
    @Volatile var demo = false           // 시연 모드 중(글쓰기 제한 등을 풀어준다)
    @Volatile var ascentM = 0            // 선택한 방향의 오르막 합계(m)
    @Volatile var pendingSummaryId = 0L  // 끝난 러닝 결과 팝업을 아직 못 보여줬을 때 그 기록 id
    @Volatile var completed = false   // 완주 목표로 종점에 도착해서 끝났는지
    val points = CopyOnWriteArrayList<Pt>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    fun changed() { main.post { listeners.forEach { it() } } }

    fun reset() { steps = null; distanceM = 0.0; hr = 0; hrSource = ""; points.clear(); startMs = System.currentTimeMillis(); startElapsedMs = android.os.SystemClock.elapsedRealtime() }

    fun elapsedSec(): Long = if (running) (android.os.SystemClock.elapsedRealtime() - startElapsedMs) / 1000 else 0

    fun timeStr(sec: Long = elapsedSec()): String {
        val h = sec / 3600; val m = sec % 3600 / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
    fun paceStr(sec: Long = elapsedSec(), dist: Double = distanceM): String {
        if (dist < 20) return "--'--\""
        val p = sec / (dist / 1000.0)
        return "%d'%02d\"".format((p / 60).toInt(), (p % 60).toInt())
    }
    fun distStr(dist: Double = distanceM) = "%.2f km".format(dist / 1000.0)
    /** 목표 진행률 0..1 (자유 러닝이면 -1) */
    fun progress(): Double = when (goalType) {
        1 -> (distanceM / goalValue).coerceIn(0.0, 1.0)
        2 -> (elapsedSec() / goalValue).coerceIn(0.0, 1.0)
        else -> -1.0
    }
    fun phaseName(): String {
        val p = progress(); if (p < 0) return "러닝 중"
        if (p >= 1.0) return "목표 달성!"
        if (!phasesOn) return "러닝 중"
        return if (p < 0.15) "워밍업" else if (p < 0.85) "본운동" else "쿨다운"
    }
    fun remainStr(): String = when (goalType) {
        1 -> "남은 %.2f km".format(((goalValue - distanceM) / 1000.0).coerceAtLeast(0.0))
        2 -> "남은 " + timeStr(((goalValue - elapsedSec()).toLong()).coerceAtLeast(0))
        else -> ""
    }
    fun goalStr(): String = when (goalType) { 1 -> "목표 %.0f km".format(goalValue / 1000.0); 2 -> "목표 %d분".format((goalValue / 60).toInt()); else -> "자유 러닝" }
    fun hrStr() = if (hr > 0) "$hr bpm" else "-- bpm"
}
