package com.eudero.maplib

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.FrameLayout
import com.kakao.vectormap.LatLng as KLatLng
import kotlin.math.cos

/**
 * 카카오맵 지도 위젯. 길·핀·내 위치·안내선은 모두 카카오 지도 안 요소로 그려 지도와 항상 함께 움직인다.
 * 제스처(이동·확대·회전)는 카카오 지도가 직접 처리하고, 꾹 누르면 원형 핑 휠.
 */
class EudMapView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {
    companion object {
        const val OWN_DOT_ZOOM = 18
        const val ACCENT = 0xFFB9FA03.toInt()
        /** 밝은 지도 위 길 선 색(형광 연두). */
        const val ROUTE = ACCENT
        /** 카카오맵 앱 키. 비어 있으면 예전 벡터 지도(MapLibre)로 돌아간다. */
        @JvmStatic var kakaoKey: String = ""
        /** 지도 위에 얹는 어두운 반투명 면(ARGB). 0이면 끔. */
        @JvmStatic var dimColor: Int = 0
        /** 시연 모드: 센서를 쓰지 않고 방향을 직진(위쪽)으로 고정. */
        @JvmStatic var fixedHeading: Boolean = false
    }

    private val kakao: KakaoBackground                           // 배경: 카카오맵
    private val loc = SmoothLocationProvider(ctx)
    private var connectorTarget: GeoPoint? = null
    private val connectorLine = object : android.view.View(ctx) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF006789.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp(3f)
            strokeCap = Paint.Cap.ROUND
            pathEffect = DashPathEffect(floatArrayOf(dp(1f), dp(6f)), 0f)
        }
        private var start: Point? = null
        private var end: Point? = null
        fun endpoints(a: Point?, b: Point?) {
            if (start == a && end == b) return
            start = a
            end = b
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            val a = start ?: return
            val b = end ?: return
            canvas.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), stroke)
        }
    }
    fun setApproachConnector(target: GeoPoint?) {
        connectorTarget = target
        if (target == null) connectorLine.endpoints(null, null)
    }
    private val myBubble = android.widget.ImageView(ctx).apply {
        visibility = INVISIBLE
        contentDescription = "내 위치 버블"
        scaleType = android.widget.ImageView.ScaleType.FIT_XY
    }
    val pinOverlay: PinOverlay
    val courseOverlay: CourseOverlay
    val layerOverlay: LayerOverlay          // 경사 구간(길 아래)
    private val wheel: WheelView
    private val recenter: android.widget.ImageView
    private val lockBtn: android.widget.ImageView
    /** 시점 잠금: 잠그면 내 위치를 따라가며 가는 방향이 위, 열면 지도를 마음대로 움직여도 그대로 유지 */
    var followLock = false; private set
    var onLockChanged: ((Boolean) -> Unit)? = null
    private var navBearing = 0f

    enum class MenuAction { WRITE, WARN }
    /** The wheel chooses the post type; posting always uses the current location. */
    var onMenuAction: ((MenuAction) -> Unit)? = null
    var onMapTap: (() -> Boolean)? = null
    var onLocationFix: ((GeoPoint) -> Unit)? = null
    private var wheelOpen = false
    var trackColor: Int = ROUTE

    private var navMe: GeoPoint? = null
    var demoLocation: GeoPoint? = null
    private fun me(): GeoPoint? = (if (navActive) navMe else null) ?: myLocation ?: cachedLast()
    val myLocation: GeoPoint? get() = demoLocation ?: (loc.last ?: loc.raw)?.let { GeoPoint(it.latitude, it.longitude) }

    init {
        pinOverlay = PinOverlay(); courseOverlay = CourseOverlay(); layerOverlay = LayerOverlay()
        kakao = KakaoBackground(ctx, kakaoKey)
        addView(kakao, LayoutParams(-1, -1))
        addView(connectorLine, LayoutParams(-1, -1))
        addView(myBubble, LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        kakao.onCameraSettled = { kov?.refreshSharedLanes(); kov?.onPinZoomChanged() }
        run {
            kakao.onState = { st -> if (st == "ready") post {
                val km = kakao.kakaoMap
                if (km != null && kov == null) {
                    kov = KakaoOverlays(km, ctx, resources.displayMetrics.density, courseOverlay, pinOverlay, layerOverlay) { kakao.read()?.get(2) }.also {
                        it.onBackgroundTap = { onMapTap?.invoke() == true }
                        it.onPinTap = { p -> pinOverlay.onPinTap?.invoke(p) }
                        it.onCourseTap = { c -> courseOverlay.onCourseTap?.invoke(c) }
                    }
                    kov?.setDim(dimColor != 0, dimColor)
                    kApply()
                }
            } }
        }
        post { kakaoTick() }
        recenter = android.widget.ImageView(ctx).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; setPadding(dp(7f).toInt(), dp(7f).toInt(), dp(7f).toInt(), dp(7f).toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT) }
            contentDescription = "내 위치로 이동"; elevation = dp(4f); setOnClickListener { centerOnMe() }
        }
        addView(recenter, LayoutParams(dp(48f).toInt(), dp(48f).toInt(), Gravity.BOTTOM or Gravity.END).also { it.setMargins(0, 0, dp(13f).toInt(), dp(14f).toInt()) })
        lockBtn = android.widget.ImageView(ctx).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; setPadding(dp(9f).toInt(), dp(9f).toInt(), dp(9f).toInt(), dp(9f).toInt()); visibility = GONE
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT) }
            contentDescription = "내 위치 고정하기"; elevation = dp(4f); setImageBitmap(LockIcons.draw(128, false))
            setOnClickListener { setFollowLock(!followLock) }
        }
        addView(lockBtn, LayoutParams(dp(48f).toInt(), dp(48f).toInt(), Gravity.BOTTOM or Gravity.END).also { it.setMargins(0, 0, dp(14f).toInt(), dp(14f + 56f).toInt()) })
        wheel = WheelView(ctx).apply {
            visibility = GONE
            items = listOf(
                WheelView.Item("✏", "글쓰기", ACCENT),
                WheelView.Item("⚠", "신고", 0xFFFF5252.toInt()))
        }
        addView(wheel, LayoutParams(-1, -1))
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /** 앱이 가진 아이콘 이미지로 버튼/핑 휠을 바꿔 끼운다 */
    fun setRecenterIcon(resource: Int) { recenter.setImageResource(resource) }
    fun setWheelIcons(write: Bitmap?, photo: Bitmap?, report: Bitmap?) { wheel.items.getOrNull(0)?.bmp = write; wheel.items.getOrNull(1)?.bmp = report }

    /** 내 위치 핀 (흰색 유리 물방울, 프로필 얼굴). 아래쪽 뾰족한 끝이 실제 위치. */
    fun setMeMarker(face: Bitmap?) {
        val image = bubbleImage(context, face, Color.WHITE, SINGLE_BUBBLE_WIDTH_DP)
        myBubble.setImageBitmap(image)
        myBubble.layoutParams = LayoutParams(image.width, image.height, Gravity.TOP or Gravity.LEFT)
    }

    private fun startWheel(x: Float, y: Float) {
        wheelOpen = true
        wheel.open(x, y, width, height)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, x, y, 0)
        kakao.forwardTouch(cancel); cancel.recycle()
    }

    private var lastMeKey = ""
    // 나침반: 폰 방향 센서로 내가 보는 방향을 읽는다
    private var heading: Float? = null
    private var mapUp = 0f; private var mapUpAt = 0L
    private val sm by lazy { context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager }
    private val rotListener = object : android.hardware.SensorEventListener {
        private val R = FloatArray(9); private val O = FloatArray(3)
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            android.hardware.SensorManager.getRotationMatrixFromVector(R, e.values)
            android.hardware.SensorManager.getOrientation(R, O)
            val az = ((Math.toDegrees(O[0].toDouble()) + 360) % 360).toFloat()
            val h = heading
            heading = if (h == null) az else { var d = az - h; while (d > 180) d -= 360; while (d < -180) d += 360; (h + d * 0.18f + 360) % 360 }
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }
    private var compassOn = false
    private fun startCompass() {
        if (compassOn || fixedHeading) return
        val s = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR) ?: return
        compassOn = sm.registerListener(rotListener, s, android.hardware.SensorManager.SENSOR_DELAY_UI)
    }
    private fun stopCompass() { if (compassOn) { sm.unregisterListener(rotListener); compassOn = false } }
    private var lastKnownAt = 0L; private var lastKnownVal: GeoPoint? = null
    private fun cachedLast(): GeoPoint? {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastKnownAt > 3000) { lastKnownAt = now; lastKnownVal = lastKnown() }
        return lastKnownVal
    }
    private fun kakaoTick() {
        kov?.let { o ->
            o.sync()
            val l = me()
            connectorTarget?.let { target ->
                connectorLine.endpoints(l?.let { screenPoint(it.latitude, it.longitude) },
                    screenPoint(target.latitude, target.longitude))
            }
            if (l != null) {
                if (myBubble.drawable != null) screenPoint(l.latitude, l.longitude)?.let { point ->
                    myBubble.translationX = point.x - myBubble.layoutParams.width / 2f
                    myBubble.translationY = (point.y - myBubble.layoutParams.height).toFloat()
                    myBubble.visibility = VISIBLE
                }
                val key = "${l.latitude},${l.longitude},$navActive,$navBearing"
                if (key != lastMeKey) { lastMeKey = key; o.updateNavigationArrow(l.latitude, l.longitude, navActive, navBearing - mapUp) }
                // 보는 방향(부채꼴): 달리는 중(내비)에는 진행 방향 화살촉이 대신한다. 지도를 돌려 놓았으면 그만큼 보정.
                val now = android.os.SystemClock.uptimeMillis()
                if (now - mapUpAt > 250) { mapUpAt = now; mapUp = kakao.upBearing()
                    kakao.read()?.get(2)?.let { mpp ->
                        myBubble.alpha = 0.5f + 0.5f * ((mpp - 0.45) / 0.9).coerceIn(0.0, 1.0).toFloat()
                    }
                    o.mapUp = mapUp; if (navActive) lastMeKey = "" }
                val hd: Float? = if (!navActive) null else if (fixedHeading) (navBearing - mapUp + 360) % 360 else heading?.let { (it - mapUp + 360) % 360 }
                o.updateHeading(l.latitude, l.longitude, hd)
            }
        }
        postOnAnimation { kakaoTick() }
    }

    /** 처음 위치를 받아 지도를 거기로 맞춘다(사용자가 직접 움직이기 전까지 한 번). */
    private var centeredOnce = false
    private var wantCenter = false
    private fun lastKnown(): GeoPoint? {
        if (!hasLocationPermission()) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        var best: android.location.Location? = null
        for (p in listOf("gps", "network", "fused", "passive")) runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { if (best == null || it.time > best!!.time) best = it }
        return best?.let { GeoPoint(it.latitude, it.longitude) }
    }
    private fun flyToMe(): Boolean {
        val me = me() ?: return false
        kakao.recenter(me.latitude, me.longitude); return true
    }
    /** 내 위치를 잡을 때까지(최대 timeoutMs) 기다렸다가 지도를 거기로 맞추고 알려준다. 못 잡으면 false. */
    val startupLabelsReady get() = myBubble.visibility == VISIBLE && kov?.startupLabelsReady == true
    fun afterMapFrame(done: () -> Unit) = kakao.afterMapFrame(done)
    fun whenLocated(timeoutMs: Long, done: (Boolean) -> Unit) {
        currentReq = false
        enableMyLocation()
        val t0 = android.os.SystemClock.uptimeMillis()
        val r = object : Runnable { override fun run() {
            if (!isAttachedToWindow) return
            if (!hasLocationPermission()) { if (android.os.SystemClock.uptimeMillis() - t0 > timeoutMs) { done(false); return }; postDelayed(this, 300); return }
            enableMyLocation()
            val fix = loc.raw
            if (fix != null && android.os.SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos in 0L..30_000_000_000L &&
                showStartArea(GeoPoint(fix.latitude, fix.longitude))) { centeredOnce = true; done(true); return }
            if (android.os.SystemClock.uptimeMillis() - t0 > timeoutMs) { done(false); return }
            postDelayed(this, 300)
        } }
        r.run()
    }
    /** 화면 비율에 관계없이 처음 잡은 위치 주변 500m를 담는다. */
    private fun showStartArea(point: GeoPoint? = me()): Boolean {
        val center = point ?: return false
        val latDelta = 500.0 / 111_320.0
        val lonDelta = latDelta / cos(Math.toRadians(center.latitude)).coerceAtLeast(0.01)
        kakao.fitBounds(center.latitude - latDelta, center.latitude + latDelta,
            center.longitude - lonDelta, center.longitude + lonDelta, 0, animate = false)
        return true
    }
    private fun kakaoLevelFor(osmZoom: Double) = Math.round(osmZoom - 1.0).toInt()
    /** 시작 위치 지정 (내 위치를 아직 모를 때만 쓰는 기본 위치). */
    fun setStart(lat: Double, lon: Double, osmZoom: Double) {
        if (centeredOnce) return
        if (showStartArea()) { centeredOnce = true; return }
        wantCenter = true
        kakao.moveTo(lat, lon, kakaoLevelFor(osmZoom), 0.0, 0)
    }

    fun screenPoint(lat: Double, lon: Double) = kakao.kakaoMap?.toScreenPoint(KLatLng.from(lat, lon))

    fun focusWarning(pin: Pin?) {
        kov?.focusWarning(pin)
        if (pin != null) kakao.moveTo(pin.lat, pin.lon, 20, null, 900)
    }
    fun warningCentered(pin: Pin): Boolean {
        val point = screenPoint(pin.lat, pin.lon) ?: return false
        return kakao.zoomLevel >= 20 && kotlin.math.hypot(point.x - width / 2.0, point.y - height / 2.0) < dp(5f)
    }

    fun zoomToGroup(pins: List<Pin>): Boolean {
        val map = kakao.kakaoMap ?: return false
        val maxZoom = minOf(map.maxZoomLevel, map.cameraMaxLevel)
        if (map.zoomLevel >= maxZoom || (navActive && followLock)) return false
        val first = pins.first()
        if (pins.all { GeoPoint(first.lat, first.lon).distanceToAsDouble(GeoPoint(it.lat, it.lon)) < 1 }) return false
        kakao.moveTo(pins.map { it.lat }.average(), pins.map { it.lon }.average(), minOf(map.zoomLevel + 2, maxZoom), null, 300)
        return true
    }
    /** 시연용: 지정한 지점으로 카메라 이동. */
    fun moveToPoint(lat: Double, lon: Double, level: Int, animMs: Int = 600) { kakao.moveTo(lat, lon, level, 0.0, animMs) }

    private var downX = 0f; private var downY = 0f
    private val slop by lazy { android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat() }
    private val longPress = Runnable {
        if (kakao.kakaoMap != null) startWheel(downX, downY)
    }

    fun fit(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, extraSouth: Double = 0.0, animate: Boolean = true) {
        val h = maxLat - minLat
        kakao.fitBounds(minLat - h * extraSouth, maxLat, minLon, maxLon, dp(50f).toInt(), animate = animate)
    }
    var bottomInsetDp: Float = 0f
        set(v) { field = v; (recenter.layoutParams as LayoutParams).bottomMargin = dp(14f + v).toInt(); recenter.requestLayout(); (lockBtn.layoutParams as LayoutParams).bottomMargin = dp(14f + v + 56f).toInt(); lockBtn.requestLayout() }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!wheelOpen) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    postDelayed(longPress, 480)
                }
                MotionEvent.ACTION_MOVE -> if (kotlin.math.hypot(ev.x - downX, ev.y - downY) > slop) {
                    removeCallbacks(longPress)
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                }
                else -> {
                    removeCallbacks(longPress)
                }
            }
        }
        if (!wheelOpen) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (wheel.move(ev.x, ev.y)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            MotionEvent.ACTION_UP -> {
                wheel.move(ev.x, ev.y)
                val s = wheel.selected; closeWheel()
                if (s >= 0) onMenuAction?.invoke(MenuAction.values()[s])
            }
            MotionEvent.ACTION_CANCEL -> closeWheel()
        }
        return true
    }
    private fun closeWheel() { wheelOpen = false; wheel.close() }

    fun hasLocationPermission() =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private var fixHandled = false
    private var currentReq = false
    /** 한 번 즉시 요청: 융합/네트워크/GPS 중 되는 것으로 첫 위치를 빨리 받는다. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun requestCurrent() {
        if (currentReq || android.os.Build.VERSION.SDK_INT < 30) return
        currentReq = true
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        for (p in listOf("fused", "network", "gps")) runCatching {
            if (lm.isProviderEnabled(p)) lm.getCurrentLocation(p, null, context.mainExecutor) { l -> if (l != null) post { loc.inject(l); onFix() } }
        }
    }
    fun enableMyLocation() {
        if (!hasLocationPermission()) return
        loc.start { _ -> onFix() }
        requestCurrent()
        if (!centeredOnce) { if (showStartArea()) centeredOnce = true }
    }
    private fun onFix() {
        if (!fixHandled) {
            fixHandled = true
            if (!centeredOnce || wantCenter) { if (showStartArea()) { centeredOnce = true; wantCenter = false } }
        }
        if (waitingMe) { waitingMe = false; flyToMe() }
        myLocation?.let { onLocationFix?.invoke(it) }
    }
    private var waitingMe = false
    /** 내 위치 버튼: 위치를 알면 바로 그곳으로, 아직 모르면 찾는 대로 이동한다. */
    fun centerOnMe() {
        enableMyLocation()
        if (!hasLocationPermission()) { if (!fixedHeading) android.widget.Toast.makeText(context, "위치 권한을 허용하면 내 위치로 갈 수 있어요", android.widget.Toast.LENGTH_SHORT).show(); return }
        if (!flyToMe()) { waitingMe = true; currentReq = false; requestCurrent(); if (!fixedHeading) android.widget.Toast.makeText(context, "내 위치를 찾는 중이에요", android.widget.Toast.LENGTH_SHORT).show() }
    }

    private val kLineState = HashMap<String, Triple<List<GeoPoint>, Int, Float>>()
    private var kFlowState: List<GeoPoint> = emptyList()
    private var kov: KakaoOverlays? = null
    private var kFlag: GeoPoint? = null
    private var kEntry: GeoPoint? = null
    private fun kApply() {
        val o = kov ?: return
        for ((key, v) in kLineState) o.setLine(key, v.first.map { KLatLng.from(it.latitude, it.longitude) }, v.second, v.third)
        o.setFlow(kFlowState.map { KLatLng.from(it.latitude, it.longitude) })
        o.setEndFlag(kFlag?.latitude, kFlag?.longitude)
        o.setGuideEntry(kEntry)
    }
    private fun line(pts: List<GeoPoint>, color: Int, width: Float, key: String) {
        if (pts.size < 2 || width <= 0f) { kLineState.remove(key); kov?.setLine(key, emptyList(), color, 0f) }
        else { kLineState[key] = Triple(pts, color, width); kov?.setLine(key, pts.map { KLatLng.from(it.latitude, it.longitude) }, color, width) }
    }

    private fun fit(points: List<GeoPoint>) {
        if (points.size < 2) return
        kakao.fitBounds(points.minOf { it.latitude }, points.maxOf { it.latitude }, points.minOf { it.longitude }, points.maxOf { it.longitude }, dp(60f).toInt())
    }

    /** 달린 경로 선. */
    fun setTrack(points: List<GeoPoint>, fit: Boolean = true) {
        line(points, trackColor, 17f, "track")
        if (fit) fit(points)
    }

    /** 경사 구간과 신호등·횡단보도 레이어를 켜거나 끈다 */
    fun setLayers(segs: List<Seg>, dots: List<Dot>, on: Boolean) {
        layerOverlay.segs = segs; layerOverlay.dots = dots; layerOverlay.showSlope = on   // 미리보기에서만 켠다
    }

    fun previewJoin(approach: List<GeoPoint>, entry: GeoPoint?) {
        kEntry = entry
        kov?.setGuideEntry(entry)
        line(approach, 0xFF0285AF.toInt(), 19f, "approach")
        if (approach.size >= 2) startFlow(approach)
    }

    fun showCourseArrows(path: List<GeoPoint>) = startFlow(path)

    fun frameSelection(points: List<GeoPoint>, cover: Int, animate: Boolean) {
        if (points.isEmpty()) return
        kakao.fitBounds(points.minOf { it.latitude }, points.maxOf { it.latitude },
            points.minOf { it.longitude }, points.maxOf { it.longitude },
            dp(40f).toInt(), cover, animate)
    }

    /** 안내 경로: 합류길까지(approach)는 흰색, 남은 코스(course)는 선택한 길의 노선색. 위로 신호등·횡단보도, 맨 위에 흐르는 화살표 */
    fun setGuide(approach: List<GeoPoint>, course: List<GeoPoint>, origin: GeoPoint, cover: Int, entry: GeoPoint? = null) {
        line(approach, 0xFF0285AF.toInt(), 19f, "approach")
        line(course, courseOverlay.selected?.lineColor ?: ROUTE, 20f, "guide")
        setFocus(true)
        startFlow(approach.dropLast(1) + course)
        val visiblePath = if (entry == null) course else approach + entry
        frameSelection(listOf(origin) + visiblePath, cover, false)
        kFlag = course.lastOrNull(); kov?.setEndFlag(kFlag?.latitude, kFlag?.longitude)
        kEntry = entry; kov?.setGuideEntry(entry)
    }
    fun updateApproach(approach: List<GeoPoint>, course: List<GeoPoint>) {
        line(approach, 0xFF0285AF.toInt(), 19f, "approach")
        startFlow(approach.dropLast(1) + course)
    }
    fun clearGuide() { setApproachConnector(null); kakao.clearFitPadding(); kFlag = null; kEntry = null; kov?.setEndFlag(null, null); kov?.setGuideEntry(null); line(emptyList(), ROUTE, 0f, "guide"); line(emptyList(), Color.WHITE, 0f, "approach"); setFocus(false); stopFlow() }

    /** The guide is drawn above the background courses. */
    private fun setFocus(on: Boolean) { courseOverlay.guiding = on }

    private fun startFlow(points: List<GeoPoint>) { kFlowState = points; kov?.setFlow(points.map { KLatLng.from(it.latitude, it.longitude) }) }
    private fun stopFlow() { kFlowState = emptyList(); kov?.setFlow(emptyList()) }
    val hasGuide get() = kLineState.containsKey("guide")

    fun setLockVisible(v: Boolean) { lockBtn.visibility = if (v) VISIBLE else GONE }
    /** 내 위치로 돌아가기 버튼 보이기/숨기기(길 안내 창이 열려 있을 땐 숨김). */
    fun setRecenterVisible(v: Boolean) { recenter.visibility = if (v && !navActive) VISIBLE else GONE }
    fun setFollowLock(on: Boolean) {
        followLock = on
        lockBtn.contentDescription = if (on) "내 위치 고정 풀기" else "내 위치 고정하기"
        lockBtn.isSelected = on
        kakao.lockPan(navActive && on)
        if (navActive && on) navMe?.let { navTo(it, navBearing) }
        lockBtn.setImageBitmap(LockIcons.draw(128, on))
        onLockChanged?.invoke(on)
    }
    var bottomInsetLockDp = 0f

    /** 내비게이션 모드: 내 위치를 화면 가운데 두고 사용자가 정한 줌과 회전을 유지하며 따라간다 */
    var navActive = false; private set
    fun setNav(on: Boolean) {
        kakao.clearFitPadding()
        navActive = on
        if (on && resumed) startCompass() else if (!on) { stopCompass(); heading = null; kov?.updateHeading(0.0, 0.0, null) }
        recenter.visibility = if (on) GONE else VISIBLE
        setLockVisible(on)   // 자물쇠는 길 안내(달리기) 중에만
        kakao.lockPan(on && followLock)
        if (!on) navMe = null

    }
    fun navTo(me: GeoPoint, bearing: Float) {
        if (!navActive) return
        navBearing = bearing; navMe = me
        if (!followLock) return
        kakao.followPosition(me.latitude, me.longitude)
    }

    private var resumed = false
    fun onResume() { resumed = true; if (navActive) startCompass(); kakao.onResume(); if (hasLocationPermission()) loc.start { _ -> onFix() } }
    fun onPause() { resumed = false; stopCompass(); kakao.onPause(); loc.stop() }
    fun onDestroy() { stopFlow(); kakao.onDestroy() }
}
