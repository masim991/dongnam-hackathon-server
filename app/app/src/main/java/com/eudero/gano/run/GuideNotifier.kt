package com.eudero.gano.run

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.eudero.gano.R
import com.eudero.gano.ui.MainActivity

/** 길 안내 중 알림. 상단 알림창을 내리면 "시연" 버튼이 있다(시연 모드 시작). */
object GuideNotifier {
    private const val CH = "guide"; private const val ID = 8

    fun show(c: Context, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "길 안내", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(c, 10, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val sim = PendingIntent.getActivity(c, 11, Intent(c, MainActivity::class.java).putExtra("demo", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(ID, Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_stat_run).setContentTitle("길 안내 중").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat_run), "시연", sim).build()).build())
    }

    fun hide(c: Context) { c.getSystemService(NotificationManager::class.java).cancel(ID) }
}
