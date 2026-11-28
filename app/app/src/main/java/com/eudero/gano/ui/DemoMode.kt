package com.eudero.gano.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.eudero.gano.R
import android.view.View
import com.eudero.gano.run.RunState
import com.eudero.maplib.EudMapView
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** 시연 버튼 한 번으로 실제 지도, 안내, 완주 결과, 기록 상세와 버블을 차례로 보여준다. */
class DemoMode(private val act: MainActivity) {
    private var playback: Job? = null
    private var writer: PostActivity? = null
    private var detail: RunDetailActivity? = null
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, s: Bundle?) {
            if (a is PostActivity && a.intent.getBooleanExtra("demoDraft", false)) writer = a
            if (a is RunDetailActivity && a.intent.getBooleanExtra("demoDetail", false)) detail = a
        }
        override fun onActivityDestroyed(a: Activity) { if (a === detail) detail = null; if (a === writer) writer = null }
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
    }

    private suspend fun awaitReady(check: () -> Boolean) {
        withTimeout(30_000) { while (!check()) delay(50) }
    }

    fun start() {
        if (playback?.isActive == true || RunState.running) return
        val map = act.mapHost()?.nativeFrag ?: return
        playback = act.lifecycleScope.launch {
            val goal = RunState.goalType
            val value = RunState.goalValue
            RunState.demo = true
            RunState.goalType = 3
            RunState.goalValue = 0.0
            EudMapView.fixedHeading = true
            act.application.registerActivityLifecycleCallbacks(callbacks)
            try {
                act.showTab(R.id.nav_map)
                if (!map.demoPrepare()) return@launch
                val tour = async { map.prepareDemoTour() }
                delay(3600)
                map.demoCourseTouch()
                map.demoSelect()
                delay(3200)
                map.demoGo()
                delay(8000)
                awaitReady { map.demoGuideReady() }
                for (direction in 0..1) {
                    act.mapHost()?.demoPickDir(direction)
                    delay(4000)
                }
                act.mapHost()?.showDemoWeather()
                delay(6500)
                map.demoStartRun()
                awaitReady { map.demoResultId() != null }
                val runId = map.demoResultId() ?: return@launch
                delay(4000)
                map.demoSummaryTouch()
                map.demoDismissSummary()
                act.findViewById<View>(R.id.nav_record)?.demoTouch()
                act.showTab(R.id.nav_record)
                delay(3600)
                (act.supportFragmentManager.findFragmentByTag("record") as? RecordFragment)?.demoRowTouch()
                act.startActivity(Intent(act, RunDetailActivity::class.java).putExtra("id", runId).putExtra("demoDetail", true))
                awaitReady { detail?.trackReady == true }
                delay(8000)
                detail?.window?.decorView?.findViewWithTag<View>("backButton")?.demoTouch()
                detail?.finish()
                awaitReady { act.hasWindowFocus() }
                act.findViewById<View>(R.id.nav_map)?.demoTouch()
                act.showTab(R.id.nav_map)
                awaitReady { map.isResumed && map.view?.isShown == true }
                map.demoAtFinish()
                awaitReady { map.finishBubblesReady }
                delay(1900)
                delay(1000)
                map.demoCompose()
                awaitReady { writer?.hasWindowFocus() == true }
                writer?.writeDemoPost()
                awaitReady { act.hasWindowFocus() && map.draftPosted }
                delay(650)
                map.demoFocusWarning()
                awaitReady { map.warningCentered }
                delay(1500)
                delay(650)
                map.demoTapWarning()
                awaitReady { map.warningOpen }
                delay(6000)
                map.demoPostTouch()
                map.demoDismissPost()
                delay(900)
                for ((index, stop) in tour.await().withIndex()) {
                    map.demoTourStop(stop)
                    delay(1550L - index * 65L)
                    stop.overview.visibility = View.GONE
                    stop.elevation.visibility = View.VISIBLE
                    delay(2700)
                }
            } catch (_: TimeoutCancellationException) {
                Toast.makeText(act, "시연 화면을 불러오지 못했어요. 다시 시작해 주세요.", Toast.LENGTH_LONG).show()
            } finally {
                if (RunState.running && RunState.sim) {
                    act.startService(Intent(act, com.eudero.gano.run.RunService::class.java)
                        .setAction(com.eudero.gano.run.RunService.CANCEL_DEMO))
                }
                writer?.finish()
                detail?.finish()
                act.application.unregisterActivityLifecycleCallbacks(callbacks)
                if (map.view != null && map.isAdded) map.demoEnd()
                RunState.goalType = goal
                RunState.goalValue = value
                RunState.demo = false
                EudMapView.fixedHeading = false
            }
        }
    }
    fun cancel() { playback?.cancel() }
}

/** Only the scripted action calls this, in the window containing its actual target. */
internal suspend fun View.demoTouch(x: Float = width / 2f, y: Float = height / 2f) {
    if (!RunState.demo || !isShown) return
    val root = rootView as? android.view.ViewGroup ?: return
    val pos = IntArray(2); val origin = IntArray(2)
    getLocationOnScreen(pos); root.getLocationOnScreen(origin)
    val cx = pos[0] - origin[0] + x; val cy = pos[1] - origin[1] + y
    val density = resources.displayMetrics.density
    val radius = (30 * density).toInt()
    val start = android.os.SystemClock.uptimeMillis()
    val mark = object : View(context) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: android.graphics.Canvas) {
            val phase = ((android.os.SystemClock.uptimeMillis() - start) / 420f).coerceIn(0f, 1f)
            val r = (10f + 15f * phase) * density
            paint.style = android.graphics.Paint.Style.STROKE
            paint.color = 0xDD19200E.toInt(); paint.strokeWidth = 4*density
            canvas.drawCircle(width/2f, height/2f, r, paint)
            paint.color = EudMapView.ACCENT; paint.strokeWidth = 2*density
            canvas.drawCircle(width/2f, height/2f, r, paint)
            paint.style = android.graphics.Paint.Style.FILL; paint.color = android.graphics.Color.WHITE
            canvas.drawCircle(width/2f, height/2f, 5*density, paint)
            if (phase < 1f) postInvalidateOnAnimation()
        }
    }
    mark.layout(cx.toInt()-radius, cy.toInt()-radius, cx.toInt()+radius, cy.toInt()+radius)
    root.overlay.add(mark)
    try { delay(420) } finally { root.overlay.remove(mark) }
}
