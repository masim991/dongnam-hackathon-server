package com.eudero.gano.run

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import com.eudero.gano.R
import com.eudero.gano.data.RouteIcon
import com.eudero.gano.data.RunStore
import com.eudero.gano.data.SavedRun
import com.eudero.gano.health.HeartRate
import com.eudero.gano.ui.MainActivity
import kotlinx.coroutines.*

/** 포그라운드 서비스: 화면이 꺼져도 기록 + 알림(잠금화면/상단 칩/Now Bar) + 홈 위젯 갱신. */
class RunService : Service(), LocationListener, android.hardware.SensorEventListener {
    private val sensors by lazy { getSystemService(android.hardware.SensorManager::class.java) }
    private var phoneSteps: Long? = null
    override fun onSensorChanged(event: android.hardware.SensorEvent) {
        if (RunState.running && !RunState.sim && event.sensor.type == android.hardware.Sensor.TYPE_STEP_DETECTOR) {
            phoneSteps = (phoneSteps ?: 0) + event.values.first().toLong()
            RunState.steps = phoneSteps
        }
    }
    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}

    companion object {
        const val START = "start"; const val STOP = "stop"; const val SIM = "sim"
        const val CANCEL_DEMO = "cancelDemo"
        private const val CH = "run"; private const val ID = 7
        fun start(c: Context) { c.startForegroundService(Intent(c, RunService::class.java).setAction(START)) }
        /** 시연용 시험 달리기: 안내 경로를 따라 자동으로 달린다 (알림창 버튼으로만 시작). */
        fun startSim(c: Context) { c.startForegroundService(Intent(c, RunService::class.java).setAction(SIM)) }
        fun stop(c: Context) = c.startService(Intent(c, RunService::class.java).setAction(STOP))
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tickCount = 0
    private var hrSum = 0L; private var hrN = 0
    private var last: Location? = null
    private val filter = com.eudero.maplib.LocationFilter()
    private var lastGpsAt = 0L
    private var lastPhase = ""

    private val tick = object : Runnable {
        override fun run() {
            if (!RunState.running) return
            if (!RunState.sim && tickCount % 5 == 0) {
                val started = RunState.startMs
                scope.launch {
                    val (bpm, src) = runCatching { HeartRate.latest(applicationContext, started) }.getOrDefault(0 to "")
                    withContext(Dispatchers.Main) {
                        if (RunState.running && RunState.startMs == started && bpm > 0) {
                            RunState.hr = bpm; RunState.hrSource = src; hrSum += bpm; hrN++
                        }
                    }
                }
            }
            tickCount++
            getSystemService(NotificationManager::class.java).notify(ID, buildNotification())
            RunWidget.refresh(applicationContext)
            RunState.changed()
            handler.postDelayed(this, 1000)
        }
    }

    private var demoPlayback = false
    private var simStartedAt = 0L
    private val simFrame = object : Runnable {
        override fun run() {
            if (!RunState.running || !RunState.sim) return
            val path = RunState.simPath ?: return
            val elapsed = android.os.SystemClock.elapsedRealtime() - simStartedAt
            val total = simCum.last()
            val distance = if (demoPlayback) total * (elapsed / 14_000.0).coerceIn(0.0, 1.0)
                else (RunState.simSpeed * elapsed / 1000.0).coerceAtMost(total)
            val end = simCum.binarySearch(distance).let { if (it < 0) -it - 1 else it }.coerceIn(1, path.lastIndex)
            val a = path[end - 1]
            val b = path[end]
            val fraction = (distance - simCum[end - 1]) / (simCum[end] - simCum[end - 1]).coerceAtLeast(0.0001)
            val point = if (distance >= total) path.last() else com.eudero.maplib.GeoPoint(
                a.latitude + (b.latitude - a.latitude) * fraction, a.longitude + (b.longitude - a.longitude) * fraction)
            RunState.points.add(RunState.Pt(point.latitude, point.longitude, System.currentTimeMillis()))
            RunState.distanceM = distance
            RunState.changed()
            if (distance >= total) {
                RunState.completed = true
                buzz()
                finish()
            } else handler.postDelayed(this, 33)
        }
    }

    /** 완주 목표: 선택한 길의 종점 30m 안에 도착하면 자동으로 끝낸다 */
    private fun checkArrive(lat: Double, lon: Double) {
        RunState.pendingEntry?.let { entry ->
            if (entry.distanceToAsDouble(com.eudero.maplib.GeoPoint(lat, lon)) >
                com.eudero.gano.data.GuidePlanner.ENTRY_RADIUS_M) return
            RunState.pendingEntry = null
        }
        if (RunState.goalType != 3 || !RunState.running) return
        val end = RunState.simPath?.lastOrNull() ?: return
        if (RunState.distanceM > 100 && end.distanceToAsDouble(com.eudero.maplib.GeoPoint(lat, lon)) <= 30.0) {
            RunState.completed = true; buzz()
            finish()
        }
    }

    /** 러닝이 끝났을 때(종료 버튼·목표 달성·완주) 소리와 진동이 있는 알림 */
    private fun notifyDone(run: SavedRun) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("done", "달리기 결과", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        val km = "%.2f km".format(run.distanceM / 1000.0)
        nm.notify(9, Notification.Builder(this, "done").setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle(if (RunState.completed) "완주했어요! 🎉" else "달리기 끝! 수고했어요")
            .setContentText("$km · ${RunState.timeStr(run.durationSec)} · ${RunState.paceStr(run.durationSec, run.distanceM)}/km")
            .setAutoCancel(true).setDefaults(Notification.DEFAULT_ALL)
            .setContentIntent(PendingIntent.getActivity(this, 12, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build())
    }

    private fun buzz() {
        runCatching { getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1)) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL_DEMO) {
            if (RunState.running && demoPlayback) {
                RunState.running = false
                RunState.sim = false
                RunState.changed()
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            if (!RunState.running) stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) { START -> begin(false); SIM -> begin(true); STOP -> finish() }
        return START_NOT_STICKY
    }

    private var simCum = DoubleArray(0)

    private fun begin(sim: Boolean) {
        if (RunState.running) return
        if (sim && (RunState.simPath?.size ?: 0) < 2) { stopSelf(); return }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "러닝 기록", NotificationManager.IMPORTANCE_LOW).apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
        RunState.reset(); RunState.running = true; RunState.sim = sim; RunState.completed = false
        if (sim) RunState.pendingEntry = null
        demoPlayback = sim && RunState.demo
        tickCount = 0; hrSum = 0; hrN = 0; last = null
        filter.reset(); filter.snapPath = RunState.simPath?.map { it.latitude to it.longitude }
        if (sim) { val p = RunState.simPath!!; simCum = DoubleArray(p.size); for (i in 1 until p.size) simCum[i] = simCum[i - 1] + p[i - 1].distanceToAsDouble(p[i]); simStartedAt = android.os.SystemClock.elapsedRealtime() }
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(ID, buildNotification())
        val lm = getSystemService(LocationManager::class.java)
        if (!sim) try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 3f, this)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 4000, 5f, this)
        } catch (_: SecurityException) { }
        phoneSteps = null
        if (!sim && HeartRate.linked(this) && (Build.VERSION.SDK_INT < 29 ||
                checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            sensors.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_DETECTOR)?.let {
                if (sensors.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)) { phoneSteps = 0; RunState.steps = 0 }
            }
        }
        if (sim) simFrame.run()
        handler.post(tick)
    }

    private fun finish() {
        if (RunState.running) {
            val elapsed = RunState.elapsedSec()
            RunState.running = false
            RunState.pendingEntry = null
            sensors.unregisterListener(this)
            handler.removeCallbacks(simFrame); handler.removeCallbacks(tick)
            runCatching { getSystemService(LocationManager::class.java).removeUpdates(this) }
            var dur = elapsed
            if (RunState.sim) dur = (RunState.distanceM / 1000.0 * 330).toLong()   // 시험 달리기: 현실적인 페이스(5'30")로 기록
            if (RunState.distanceM < 100.0) {   // 100m 미만은 기록하지 않는다
                val app = applicationContext
                handler.post { android.widget.Toast.makeText(app, "100m보다 짧게 달린 건 기록되지 않아요", android.widget.Toast.LENGTH_LONG).show() }
            } else if (RunState.points.size >= 2 || dur > 10) {
                val run = SavedRun(RunState.startMs, RunState.startMs, dur, RunState.distanceM, if (hrN > 0) (hrSum / hrN).toInt() else if (RunState.sim) 138 else 0, RunState.points.toList(), RunState.courseName, emptyList(), RunState.ascentM, synced = demoPlayback,
                    healthEligible = !RunState.sim && HeartRate.linked(this), phoneSteps = phoneSteps, endMs = System.currentTimeMillis())
                runCatching { RunStore.save(this, run) }.onFailure { android.util.Log.e("Run", "save failed", it) }
                if (run.healthEligible) HeartRate.sync(this)
                runCatching { RouteIcon.save(this, run) } // 달린 길을 PNG 아이콘으로 저장
                RunState.pendingSummaryId = run.id
                notifyDone(run)                           // 끝났다고 알림(소리·진동)
                val app = applicationContext
                kotlin.concurrent.thread { runCatching { com.eudero.gano.data.Repo.syncRuns(app) } }   // 맥미니에 기록·경로 이미지 올리기
            }
            RunState.sim = false
            RunState.changed(); RunWidget.refresh(this)
        }
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onLocationChanged(raw: Location) {
        if (!RunState.running) return
        val now = System.currentTimeMillis()
        if (raw.provider == LocationManager.GPS_PROVIDER) lastGpsAt = now else if (now - lastGpsAt < 15000) return   // GPS가 있으면 네트워크 위치 무시
        val l = filter.process(raw) ?: return          // 정확도 낮음/튀는 점은 버리고, 칼만 필터로 부드럽게, 안내 경로 위로 살짝 붙임
        last?.let { p -> val d = p.distanceTo(l); if (d < 1f) return; RunState.distanceM += d }
        last = l
        RunState.points.add(RunState.Pt(l.latitude, l.longitude, System.currentTimeMillis()))
        RunState.changed()
        checkArrive(l.latitude, l.longitude)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RunService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle("러닝 중 · ${RunState.distStr()}")
            .setContentText(if (RunState.distanceM >= 20.0) "평균 페이스 ${RunState.paceStr()}/km" else "운동 시간을 기록하고 있어요")
            .setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_WORKOUT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setUsesChronometer(true).setWhen(System.currentTimeMillis() - RunState.elapsedSec() * 1000).setShowWhen(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_run), "종료", stop).build())
        if (RunState.simPath != null && !RunState.sim) b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_run), "시험", PendingIntent.getService(this, 3, Intent(this, RunService::class.java).setAction(SIM), PendingIntent.FLAG_IMMUTABLE)).build())
        return b.build()
    }

    override fun onDestroy() { sensors.unregisterListener(this); handler.removeCallbacks(simFrame); handler.removeCallbacks(tick); scope.cancel(); super.onDestroy() }
}
