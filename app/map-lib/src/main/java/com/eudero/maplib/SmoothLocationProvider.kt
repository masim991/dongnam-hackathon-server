package com.eudero.maplib

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

/** 내 위치 제공자: GPS 위주 + 필터(정확도·튀는 점·칼만). 네트워크 위치는 GPS가 한동안 없을 때만 쓴다. */
class SmoothLocationProvider(ctx: Context) : LocationListener {
    private val lm = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val filter = LocationFilter()
    private var listener: ((Location) -> Unit)? = null
    var last: Location? = null; private set
    /** 필터 전 가장 최근 위치(정확도가 낮아도). 처음 위치를 빨리 잡는 데 쓴다. */
    var raw: Location? = null; private set
    private var lastGpsAt = 0L
    private var running = false

    @SuppressLint("MissingPermission")
    fun start(l: (Location) -> Unit): Boolean {
        listener = l
        if (running) return true
        var ok = false
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this, Looper.getMainLooper()); ok = true }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 4000L, 8f, this, Looper.getMainLooper()); ok = true }
        } catch (_: SecurityException) { }
        running = ok
        return ok
    }

    /** 한 번 요청해서 받은 위치를 넣는다(첫 위치를 빨리 잡기 위함). */
    fun inject(l: Location) { raw = l; if (last == null) listener?.invoke(l) }

    fun stop() { running = false; runCatching { lm.removeUpdates(this) } }

    override fun onLocationChanged(l: Location) {
        raw = l
        val now = System.currentTimeMillis()
        if (l.provider == LocationManager.GPS_PROVIDER) lastGpsAt = now
        else if (now - lastGpsAt < 15000) return                 // GPS가 살아있으면 네트워크 위치는 무시 (튐의 주범)
        val f = filter.process(l)
        if (f == null) { if (last == null) listener?.invoke(l); return }
        last = f; listener?.invoke(f)
    }
    @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
}
