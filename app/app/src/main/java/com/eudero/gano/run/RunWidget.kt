package com.eudero.gano.run

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.eudero.gano.R
import com.eudero.gano.ui.MainActivity

/** 홈 화면 위젯: 경과 시간 / 페이스 / 심박수. */
class RunWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { m.updateAppWidget(it, build(c)) }
    }

    companion object {
        fun build(c: Context): RemoteViews = RemoteViews(c.packageName, R.layout.widget_run).apply {
            if (RunState.running) {
                setTextViewText(R.id.w_time, RunState.timeStr())
                setTextViewText(R.id.w_sub, "${RunState.distStr()} · ${RunState.paceStr()}/km · ♥ ${RunState.hrStr()}")
            } else {
                setTextViewText(R.id.w_time, "00:00")
                setTextViewText(R.id.w_sub, "대기 중 · 앱에서 러닝 시작")
            }
            setOnClickPendingIntent(R.id.w_root,
                PendingIntent.getActivity(c, 2, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        }
        fun refresh(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            m.updateAppWidget(ComponentName(c, RunWidget::class.java), build(c))
        }
    }
}
