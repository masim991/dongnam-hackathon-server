package com.eudero.maplib

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.widget.FrameLayout
import com.kakao.vectormap.GestureType
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.KakaoMapSdk
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraPosition
import com.kakao.vectormap.camera.CameraUpdateFactory
import kotlin.math.*

/**
 * 카카오맵 배경. 손가락 제스처는 카카오 지도가 직접 받고(EudMapView가 터치를 그대로 전달),
 * 위에 겹친 길·핀 층(osmdroid)은 매 프레임 카카오 지도의 실제 카메라를 읽어 그대로 따라간다.
 * 코드로 움직일 때(내 위치로, 길 전체 보기 등)는 반대로 층의 카메라를 카카오 지도에 보낸다.
 */
class KakaoBackground(ctx: Context, key: String) : FrameLayout(ctx) {
    private val view: MapView
    private var kmap: KakaoMap? = null
    /** 카카오 지도가 손가락으로 움직이는 중(관성 포함)이면 true. */
    @Volatile var driven = false
    var onCameraSettled: (() -> Unit)? = null
    var onState: ((String) -> Unit)? = null
    init {
        runCatching { KakaoMapSdk.init(ctx.applicationContext, key) }
        view = MapView(ctx); addView(view, LayoutParams(-1, -1))
        view.start(object : MapLifeCycleCallback() {
            override fun onMapDestroy() {}
            override fun onMapError(e: Exception) { Log.e("KakaoBg", "map error", e); onState?.invoke("error:" + e.message) }
        }, object : KakaoMapReadyCallback() {
            override fun onMapReady(m: KakaoMap) {
                kmap = m
                pendingMove?.let { pm -> pendingMove = null; post { pm() } }
                m.setGestureEnable(GestureType.Tilt, false)
                m.setOnCameraMoveStartListener { _, g -> if (g != GestureType.Unknown) { driven = true; gestureSince = true } }
                m.setOnCameraMoveEndListener { _, _, _ -> driven = false; post { onCameraSettled?.invoke() } }
                onState?.invoke("ready")
            }
        })
    }
    val ready get() = kmap != null
    val kakaoMap: KakaoMap? get() = kmap
    val zoomLevel: Int get() = kmap?.zoomLevel ?: 15

    /** 카카오 지도의 실제 화면 상태: [중심 위도, 중심 경도, 미터/픽셀, 위쪽 방위(도)]. */
    fun read(): DoubleArray? {
        val m = kmap ?: return null; val w = width; val h = height; if (w == 0 || h == 0) return null
        val c = runCatching { m.fromScreenPoint(w / 2, h / 2) }.getOrNull() ?: return null
        val l = runCatching { m.fromScreenPoint(0, h / 2) }.getOrNull() ?: return null
        val r = runCatching { m.fromScreenPoint(w, h / 2) }.getOrNull() ?: return null
        val k = 111320.0; val cosL = cos(Math.toRadians(c.latitude))
        val dx = (r.longitude - l.longitude) * k * cosL; val dy = (r.latitude - l.latitude) * k
        val mpp = hypot(dx, dy) / w
        val rightBearing = Math.toDegrees(atan2(dx, dy))      // 화면 오른쪽이 가리키는 방위
        return doubleArrayOf(c.latitude, c.longitude, mpp, rightBearing - 90.0)
    }

    /** 카카오 회전 부호(처음 회전시킬 때 실제로 읽어 확인한다). */
    private var rotSign = 1.0
    private var rotChecked = false
    val cameraBearing: Double? get() = read()?.get(3)

    /** 코드로 카메라 이동. level: 카카오 확대 단계(null이면 그대로), upBearing: 화면 위쪽이 가리킬 방위(null이면 그대로). */
    private var lastLevel = 15
    private var gestureSince = false   // 마지막 코드 이동 뒤에 사람이 지도를 손으로 돌렸는지
    private var lastBear = 0.0
    private val created = android.os.SystemClock.uptimeMillis()
    private var pendingMove: (() -> Unit)? = null
    fun moveTo(lat: Double, lon: Double, level: Int?, upBearing: Double?, animMs: Int = 0) {
        val m = kmap ?: run { pendingMove = { moveTo(lat, lon, level, upBearing, animMs) }; return }
        val lv = (level ?: lastLevel).coerceIn(0, 21); lastLevel = lv
        val bear = upBearing ?: lastBear; lastBear = bear; gestureSince = false
        val pos = CameraPosition.from(lat, lon, lv, 0.0, Math.toRadians(bear) * rotSign, -1.0)
        try {
            if (animMs > 0) m.moveCamera(CameraUpdateFactory.newCameraPosition(pos), com.kakao.vectormap.camera.CameraAnimation.from(animMs))
            else m.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
        } catch (e: Throwable) { Log.e("KakaoBg", "moveCamera failed", e) }
        if (upBearing != null && !rotChecked && Math.abs(upBearing) > 20) {
            rotChecked = true
            postDelayed({
                val rb = read()?.get(3) ?: return@postDelayed
                var d1 = Math.abs(((rb - upBearing) + 540) % 360 - 180); var d2 = Math.abs(((rb + upBearing) + 540) % 360 - 180)
                if (d2 < d1 && d2 < 10) { rotSign = -rotSign; Log.i("KakaoBg", "rotation sign flipped"); moveTo(lat, lon, lv, upBearing, 0) }
            }, 700)
        }
    }

    /** 지금 화면 위쪽이 가리키는 방위(도). 코드로 돌린 뒤 손으로 안 돌렸으면 우리가 준 값, 아니면 지도에서 읽는다. */
    fun upBearing(): Float = if (!gestureSince) lastBear.toFloat() else (read()?.get(3) ?: lastBear).toFloat()

    fun zoomBy(delta: Int) { val m = kmap ?: return; runCatching { m.moveCamera(if (delta > 0) CameraUpdateFactory.zoomIn() else CameraUpdateFactory.zoomOut(), com.kakao.vectormap.camera.CameraAnimation.from(250)) } }

    /** 영역이 한 화면에 들어오게 맞춘다. bottomPx: 아래쪽 패널이 가리는 만큼 여유. */
    fun fitBounds(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, padPx: Int, bottomPx: Int = 0, animate: Boolean = true) {
        val m = kmap ?: run {
            pendingMove = { fitBounds(minLat, maxLat, minLon, maxLon, padPx, bottomPx, animate) }
            return
        }
        // Reserve the sheet's actual pixels, including for nearly east-west paths.
        val bottom = bottomPx.coerceIn(0, (height - 2 * padPx - 60).coerceAtLeast(0))
        m.setPadding(0, 0, 0, bottom)
        val b = com.kakao.vectormap.LatLngBounds(com.kakao.vectormap.LatLng.from(minLat, minLon), com.kakao.vectormap.LatLng.from(maxLat, maxLon))
        val update = CameraUpdateFactory.fitMapPoints(b, padPx)
        if (animate) m.moveCamera(update, com.kakao.vectormap.camera.CameraAnimation.from(350))
        else m.moveCamera(update)
    }

    fun clearFitPadding() { kmap?.setPadding(0, 0, 0, 0) }

    fun drawRecordedTrack(points: List<GeoPoint>) {
        val map = kmap ?: return
        if (points.isEmpty()) return
        val path = points.map { com.kakao.vectormap.LatLng.from(it.latitude, it.longitude) }
        val pad = (24 * resources.displayMetrics.density).toInt()
        if (path.size > 1) {
            val style = com.kakao.vectormap.route.RouteLineStyle.from(11f, EudMapView.ROUTE, 3f, 0xFF1E2615.toInt())
            val segment = com.kakao.vectormap.route.RouteLineSegment.from(path, style)
            map.routeLineManager?.layer?.addRouteLine(com.kakao.vectormap.route.RouteLineOptions.from(segment))
        }
        for ((point, color) in listOf(path.first() to android.graphics.Color.WHITE, path.last() to EudMapView.ROUTE)) {
            val size = (12 * resources.displayMetrics.density).toInt().coerceAtLeast(6)
            val dot = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(dot)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            paint.color = color
            canvas.drawCircle(size / 2f, size / 2f, size * 0.36f, paint)
            val label = com.kakao.vectormap.label.LabelStyle.from(dot).setAnchorPoint(0.5f, 0.5f).setApplyDpScale(false)
            map.labelManager?.layer?.addLabel(com.kakao.vectormap.label.LabelOptions.from(point).setStyles(label))
        }
        if (points.all { it.latitude == points.first().latitude && it.longitude == points.first().longitude })
            moveTo(path.first().latitude, path.first().longitude, 16, null)
        else map.moveCamera(CameraUpdateFactory.fitMapPoints(path.toTypedArray(), pad))
    }

    fun recenter(lat: Double, lon: Double) {
        val map = kmap ?: run { pendingMove = { recenter(lat, lon) }; return }
        map.moveCamera(CameraUpdateFactory.newCenterPosition(com.kakao.vectormap.LatLng.from(lat, lon)))
    }

    fun followPosition(lat: Double, lon: Double) {
        if (!driven) kmap?.moveCamera(CameraUpdateFactory.newCenterPosition(com.kakao.vectormap.LatLng.from(lat, lon)))
    }

    fun lockPan(locked: Boolean) {
        kmap?.setGestureEnable(GestureType.Pan, !locked)
    }

    // Label registration precedes tile and icon composition on the map's own surface.
    // Compare small snapshots under the loading cover; timers alone miss late texture uploads.
    fun afterMapFrame(done: () -> Unit) {
        val surface = view.surfaceView ?: return
        val renderer = surface as? android.view.SurfaceHolder.Callback2 ?: return
        if (!surface.holder.surface.isValid || width <= 0 || height <= 0) return
        val w = 160
        val h = (height * w / width).coerceIn(64, 480)
        val sample = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        var previous: IntArray? = null
        var stableSince = android.os.SystemClock.uptimeMillis()
        val started = stableSince
        fun sampleFrame() {
            if (!isAttachedToWindow || !surface.holder.surface.isValid ||
                android.os.SystemClock.uptimeMillis() - started >= 14_500) return
            android.view.PixelCopy.request(surface, sample, { result ->
                if (!isAttachedToWindow) return@request
                val now = android.os.SystemClock.uptimeMillis()
                val pixels = IntArray(w * h)
                if (result == android.view.PixelCopy.SUCCESS) {
                    sample.getPixels(pixels, 0, w, 0, 0, w, h)
                    val last = previous
                    // Ignore subpixel shimmer and tiny compass movements on a real phone.
                    val changed = last == null || pixels.indices.count { i ->
                        val before = last[i]
                        val after = pixels[i]
                        kotlin.math.abs((before and 255) - (after and 255)) > 18 ||
                            kotlin.math.abs((before shr 8 and 255) - (after shr 8 and 255)) > 18 ||
                            kotlin.math.abs((before shr 16 and 255) - (after shr 16 and 255)) > 18
                    } > 16
                    if (changed) stableSince = now
                    previous = pixels
                    if (now - stableSince >= 950 && now - started >= 1600 &&
                        pixels.any { it != pixels[pixels.size / 2] }) {
                        done()
                        return@request
                    }
                } else stableSince = now
                postDelayed({ sampleFrame() }, 120)
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        renderer.surfaceRedrawNeededAsync(surface.holder) { post { sampleFrame() } }
    }

    fun forwardTouch(ev: MotionEvent) { view.dispatchTouchEvent(ev) }
    fun onResume() { view.resume() }
    fun onPause() { view.pause() }
    fun onDestroy() { runCatching { view.finish() } }
}
