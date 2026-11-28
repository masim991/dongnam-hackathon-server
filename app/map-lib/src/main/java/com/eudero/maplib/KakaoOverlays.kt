package com.eudero.maplib

import android.content.Context
import android.graphics.*
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.label.*
import com.kakao.vectormap.route.*
import kotlin.math.hypot

/**
 * 카카오 지도 "안"에 길·핀·내 위치를 그린다(지도와 함께 움직이므로 어긋남이 없다).
 * 데이터는 기존 CourseOverlay·PinOverlay·LayerOverlay 객체를 그대로 읽는다.
 */
class KakaoOverlays(private val k: KakaoMap, private val ctx: Context, private val d: Float,
                    private val courseOv: CourseOverlay, private val pinOv: PinOverlay, private val layerOv: LayerOverlay,
                    private val metersPerPixel: () -> Double?) {
    private val rl = k.routeLineManager!!
    private val lm = k.labelManager!!
    private val routeLayer = rl.layer!!   // 기본 레이어만 화면에 그려진다(별도 레이어는 안 보임). 겹침은 선마다 zOrder로.
    private val courseLabels = lm.addLayer(LabelLayerOptions.from("courseLabels").setZOrder(10100).setCompetitionType(CompetitionType.None).setClickable(true))!!
    private val pinLayer = lm.addLayer(LabelLayerOptions.from("pins").setZOrder(10060).setCompetitionType(CompetitionType.None).setClickable(true))!!
    private val bubbles = lm.addLayer(LabelLayerOptions.from("postBubbles").setZOrder(10150).setCompetitionType(CompetitionType.None).setClickable(true))!!
    // The location marker must never cover another runner’s post or warning.
    private val meLayer = lm.addLayer(LabelLayerOptions.from("me").setZOrder(10050).setCompetitionType(CompetitionType.None).setClickable(false))!!

    var onBackgroundTap: (() -> Boolean)? = null
    var onPinTap: ((Pin) -> Unit)? = null
    var onCourseTap: ((Course) -> Unit)? = null

    private fun ll(lat: Double, lon: Double) = LatLng.from(lat, lon)

    // ---------- 길 선 ----------
    private fun lineStyle(w: Float, color: Int, sw: Float, sc: Int) = RouteLineStyle.from(w, color, sw, sc)
    private fun addLine(layer: RouteLineLayer, pts: List<LatLng>, styles: RouteLineStyle, z: Int = 0): RouteLine? {
        if (pts.size < 2) return null
        val seg = RouteLineSegment.from(pts, styles)
        return runCatching { layer.addRouteLine(RouteLineOptions.from(seg).setZOrder(z)) }.getOrNull()
    }

    // ---------- 코스 ----------
    private class CL(val course: Course, val lines: List<RouteLine>, val label: Label?)
    private var cls: List<CL> = emptyList()
    private var coursesRef: List<Course>? = null
    private var courseState = ""
    private class Lane(val shared: SharedPath, val course: Course, val offset: Double) {
        var points: List<LatLng> = emptyList()
        var line: RouteLine? = null
    }
    private var lanes: List<Lane> = emptyList()
    private var laneScale = 0.0

    fun refreshSharedLanes() {
        if (!courseOv.visible || courseOv.selected != null || lanes.isEmpty()) return
        val scale = metersPerPixel()?.takeIf { it.isFinite() && it > 0.0 } ?: return
        if (kotlin.math.abs(scale - laneScale) < scale * 0.02) return
        laneScale = scale
        val styleWidth = 6f
        for (lane in lanes) {
            val center = lane.shared.points
            val offsetM = lane.offset * 7.5 * d * scale
            lane.points = center.mapIndexed { i, point ->
                val prev = center[maxOf(0, i - 1)]
                val next = center[minOf(center.lastIndex, i + 1)]
                val metersLon = 111_320 * Math.cos(Math.toRadians(point.latitude))
                val dx = (next.longitude - prev.longitude) * metersLon
                val dy = (next.latitude - prev.latitude) * 111_320
                val length = hypot(dx, dy).coerceAtLeast(0.001)
                ll(point.latitude + offsetM * dx / length / 111_320,
                    point.longitude - offsetM * dy / length / metersLon)
            }
            val style = lineStyle(styleWidth, lane.course.lineColor, 1.4f, 0xFF14171C.toInt())
            val line = lane.line
            if (line == null) lane.line = addLine(routeLayer, lane.points, style, 3)
            else { line.changeSegments(RouteLineSegment.from(lane.points, style)); line.show() }
        }
    }

    private fun pill(text: String, selected: Boolean, dimmed: Boolean = false): Bitmap {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d; typeface = MapFonts.bold; textAlign = Paint.Align.CENTER }
        val tw = p.measureText(text); val w = (tw + 18 * d).toInt(); val h = (22 * d).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (selected) 0xFF344415.toInt() else if (dimmed) 0xA016131B.toInt() else 0xFF16131B.toInt() }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 11 * d, 11 * d, bg)
        p.color = Color.WHITE
        c.drawText(text, w / 2f, h / 2f + 4.5f * d, p); return b
    }
    private val noFade = LabelTransition.from(Transition.None, Transition.None)
    private fun labelStyles(b: Bitmap, ax: Float = 0.5f, ay: Float = 1f, minLevel: Int = 0) =
        LabelStyles.from(LabelStyle.from(b).setIconTransition(noFade).setAnchorPoint(ax, ay).setApplyDpScale(false).setZoomLevel(minLevel))

    private fun syncCourses() {
        val list = courseOv.courses
        if (list !== coursesRef) {
            cls.forEach { c -> c.lines.forEach { runCatching { it.remove() } }; c.label?.let { runCatching { courseLabels.remove(it) } } }
            lanes.forEach { it.line?.remove() }
            lanes = sharedPaths(list).flatMap { path ->
                path.courses.mapIndexed { index, course -> Lane(path, course, index - (path.courses.size - 1) / 2.0) }
            }
            laneScale = 0.0
            coursesRef = list; courseState = ""
            cls = list.map { course ->
                val lines = course.lines.mapNotNull { l ->
                    addLine(routeLayer, l.lat.indices.map { ll(l.lat[it], l.lon[it]) }, lineStyle(9f, course.lineColor, 3.5f, 0xFF14171C.toInt()), 2)
                }
                val l = course.lines.maxByOrNull { it.lat.size }!!; val m = l.lat.size / 2
                val lab = runCatching { courseLabels.addLabel(LabelOptions.from(ll(l.lat[m], l.lon[m])).setStyles(labelStyles(pill(course.name, false), 0.5f, 1.4f, 12)).setClickable(true).setTag(course)) }.getOrNull()
                CL(course, lines, lab)
            }
        }
        val st = "${courseOv.visible}|${courseOv.guiding}|${courseOv.selected?.code}"
        if (st == courseState) return
        courseState = st
        if (courseOv.visible && courseOv.selected == null) refreshSharedLanes()
        else { lanes.forEach { it.line?.hide() }; laneScale = 0.0 }
        for (c in cls) {
            val sel = c.course === courseOv.selected
            val show = courseOv.visible && (courseOv.selected == null || sel) && !courseOv.guiding
            val dimmed = courseOv.guiding || (courseOv.selected != null && !sel)
            fun ink(color: Int) = if (dimmed) (color and 0xFFFFFF) or 0x72000000 else color
            for (ln in c.lines) {
                if (show) {
                    ln.show()
                    ln.setZOrder(if (sel && !courseOv.guiding) 3 else 2)
                    ln.changeStyle(lineStyle(if (sel && !courseOv.guiding) 14f else 9f,
                        ink(c.course.lineColor), if (sel && !courseOv.guiding) 4f else 3.5f, ink(0xFF14171C.toInt())))
                } else ln.hide()
            }
            c.label?.let { lb ->
                if (show) {
                    lb.show(false)
                    lb.setStyles(labelStyles(pill(c.course.name, sel, dimmed), 0.5f, 1.4f, 12))
                } else lb.hide()
            }
        }
    }

    // ---------- 경사 구간 ----------
    private var segsRef: List<Seg>? = null; private var segsOn = false
    private var slopeLines: List<RouteLine> = emptyList()
    /** 미리보기에서만: 가는 길의 오르막 구간을 빨간색으로 겹쳐 보여준다(나머지 구간은 원래 길 색 그대로). */
    private fun syncSlope() {
        val segs = layerOv.segs; val on = layerOv.showSlope
        if (segs === segsRef && on == segsOn) return; segsRef = segs; segsOn = on
        slopeLines.forEach { runCatching { it.remove() } }; slopeLines = emptyList()
        if (!on || segs.isEmpty()) return
        val out = ArrayList<RouteLine>(); var cur = ArrayList<LatLng>()
        fun flush() { if (cur.size >= 2) addLine(routeLayer, cur, lineStyle(6f, 0xFFFF525B.toInt(), 0f, Color.TRANSPARENT), 4)?.let { out.add(it) }; cur = ArrayList() }
        var pLat = 0.0; var pLon = 0.0
        for (s in segs) {
            val joined = cur.isNotEmpty() && hypot(s.aLat - pLat, s.aLon - pLon) < 2e-6
            if (!joined) { flush(); cur.add(ll(s.aLat, s.aLon)) }
            cur.add(ll(s.bLat, s.bLon)); pLat = s.bLat; pLon = s.bLon
        }
        flush(); slopeLines = out
    }

    // ---------- 미리보기 신호등 ----------
    private var sigRef: List<Dot>? = null; private var sigOn = false
    private var sigLabels: List<Label> = emptyList()
    private fun syncSignals() {
        val dots = layerOv.dots; val on = layerOv.showSlope
        if (layerOv.dots === sigRef && on == sigOn) return; sigRef = layerOv.dots; sigOn = on
        sigLabels.forEach { runCatching { pinLayer.remove(it) } }; sigLabels = emptyList()
        if (!on || dots.isEmpty()) return
        val styles = dots.map { it.signal }.distinct().associateWith { labelStyles(facilityIcon(d, it), 0.5f, 0.9f) }
        sigLabels = dots.mapNotNull { runCatching { pinLayer.addLabel(LabelOptions.from(ll(it.lat, it.lon)).setStyles(styles.getValue(it.signal)).setClickable(false)) }.getOrNull() }
    }

    // ---------- 도착 깃발 ----------
    private var flag: Label? = null
    fun setEndFlag(lat: Double?, lon: Double?) {
        flag?.let { runCatching { pinLayer.remove(it) } }; flag = null
        if (lat == null || lon == null) return
        val w = (30 * d).toInt(); val h = (38 * d).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF14171C.toInt(); strokeWidth = 3.2f * d; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
        c.drawLine(5 * d, 36 * d, 5 * d, 4 * d, ink)
        val fl = Path().apply { moveTo(5 * d, 4 * d); lineTo(27 * d, 10 * d); lineTo(5 * d, 18 * d); close() }
        c.drawPath(fl, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() })
        c.drawPath(fl, Paint(ink).apply { strokeWidth = 2f * d; strokeJoin = Paint.Join.ROUND })
        flag = runCatching { pinLayer.addLabel(LabelOptions.from(ll(lat, lon)).setStyles(labelStyles(b, 0.15f, 1f)).setClickable(false)) }.getOrNull()
    }

    private var guideEntry: Label? = null
    fun setGuideEntry(point: GeoPoint?) {
        guideEntry?.let { runCatching { pinLayer.remove(it) } }
        guideEntry = null
        if (point == null) return
        val width = (58 * d).toInt()
        val height = (28 * d).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF14171C.toInt() }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), 21 * d, 10 * d, 10 * d, paint)
        canvas.drawPath(Path().apply {
            moveTo(23 * d, 19 * d); lineTo(35 * d, 19 * d); lineTo(29 * d, 28 * d); close()
        }, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 12 * d
        canvas.drawText("합류점", width / 2f, 15 * d, paint)
        guideEntry = runCatching {
            pinLayer.addLabel(LabelOptions.from(ll(point.latitude, point.longitude))
                .setStyles(labelStyles(bitmap, 0.5f, 1f)).setClickable(false))
        }.getOrNull()
    }

    // ---------- 안내 선 (흰색 접근 / 코스별 노선색 / 달린 기록) + 흐르는 화살표 ----------
    private val named = HashMap<String, RouteLine>()
    fun setLine(key: String, pts: List<LatLng>, color: Int, width: Float) {
        val old = named[key]
        if (pts.size < 2 || width <= 0f) { if (old != null) { runCatching { old.hide() } } ; return }
        val st = lineStyle(width * 0.6f, color, 3.5f, 0xFF14171C.toInt())
        if (old != null) {   // 지웠다 다시 만들면 화면에 안 뜨는 경우가 있어서, 같은 선의 좌표만 바꿔 끼운다
            runCatching { old.changeSegments(RouteLineSegment.from(pts, st)); old.show() }
        } else addLine(routeLayer, pts, st, if (key == "approach") 5 else 3)?.let { named[key] = it }
    }
    // ---------- 흐르는 화살표: 길 위에 작은 각진 화살촉(라벨)을 일정 간격으로 놓고 가는 방향으로 천천히 옮긴다 ----------
    // 선 무늬(패턴)는 모서리에서 끊겨서, 라벨을 길 따라 움직이며 모서리에서는 방향을 바꿔 준다.
    private val flowLayer = lm.addLayer(LabelLayerOptions.from("flowLabels").setZOrder(10075).setCompetitionType(CompetitionType.None).setClickable(false))!!
    private var fLat = DoubleArray(0); private var fLon = DoubleArray(0); private var fCum = DoubleArray(0)
    private var fLabels: List<Label> = emptyList(); private var fRot = FloatArray(0)
    var mapUp = 0f      // 지금 화면 위쪽이 가리키는 방위(도). 라벨은 화면 기준으로 돌아서 이만큼 빼 준다.
    private var shownArrows = 0
    private var fOffset = 0.0; private var fLastT = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val flowTick = object : Runnable {
        override fun run() {
            if (fLabels.isEmpty()) return
            val total = fCum.last()
            val scale = metersPerPixel()?.takeIf { it.isFinite() && it > 0.0 }
            val count = if (scale == null) shownArrows.coerceAtLeast(1)
                else (total / (64 * d * scale)).toInt().coerceIn(1, fLabels.size)
            for (i in count until shownArrows) fLabels[i].hide()
            val spacing = total / count
            val now = android.os.SystemClock.uptimeMillis()
            val dt = if (fLastT == 0L) 0.0 else (now - fLastT) / 1000.0
            fLastT = now
            fOffset = (fOffset + 5.0 * dt) % total
            for (i in 0 until count) {
                val sPos = if (count == 1) total / 2 else (i * spacing + spacing / 2 + fOffset) % total
                // 구간 찾기
                var lo = 0; var hi = fCum.size - 1
                while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (fCum[mid] <= sPos) lo = mid else hi = mid }
                val segLen = (fCum[lo + 1] - fCum[lo]).coerceAtLeast(1e-6); val t = ((sPos - fCum[lo]) / segLen).coerceIn(0.0, 1.0)
                val la = fLat[lo] + (fLat[lo + 1] - fLat[lo]) * t; val lo2 = fLon[lo] + (fLon[lo + 1] - fLon[lo]) * t
                fLabels[i].moveTo(ll(la, lo2), 0)
                val dx = (fLon[lo + 1] - fLon[lo]) * Math.cos(Math.toRadians(la)); val dy = (fLat[lo + 1] - fLat[lo])
                val brg = ((Math.toDegrees(Math.atan2(dx, dy)) + 360) % 360).toFloat()
                val ang = ((brg - mapUp) % 360f + 360f) % 360f
                if (Math.abs(ang - fRot[i]) > 0.5f) { fRot[i] = ang; fLabels[i].rotateTo(Math.toRadians(ang.toDouble()).toFloat()) }
                if (i >= shownArrows) fLabels[i].show(false)
            }
            shownArrows = count
            handler.postDelayed(this, 110)
        }
    }
    private fun chevron(): Bitmap {
        val w = (15 * d).toInt(); val h = (13 * d).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val path = Path().apply { moveTo(2 * d, h - 2 * d); lineTo(w / 2f, 2.5f * d); lineTo(w - 2 * d, h - 2 * d) }   // 위쪽(진행 방향)을 가리키는 각진 V
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4.4f * d; color = 0xFF14171C.toInt(); strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER }
        val arrow = Paint(ink).apply { strokeWidth = 2.2f * d; color = Color.WHITE }
        c.drawPath(path, ink); c.drawPath(path, arrow); return b
    }
    fun setFlow(pts: List<LatLng>) {
        handler.removeCallbacks(flowTick)
        fLabels.forEach { runCatching { flowLayer.remove(it) } }; fLabels = emptyList(); fLastT = 0L; shownArrows = 0; fOffset = 0.0
        if (pts.size < 2) return
        fLat = DoubleArray(pts.size) { pts[it].latitude }; fLon = DoubleArray(pts.size) { pts[it].longitude }
        fCum = DoubleArray(pts.size)
        for (i in 1 until pts.size) { val dx = (fLon[i] - fLon[i - 1]) * Math.cos(Math.toRadians(fLat[i])) * 111320.0; val dy = (fLat[i] - fLat[i - 1]) * 111320.0; fCum[i] = fCum[i - 1] + Math.hypot(dx, dy) }
        val total = fCum.last(); if (total <= 0.0) return
        val count = (total / 25.0).toInt().coerceIn(1, 70)
        val st = labelStyles(chevron(), 0.5f, 0.5f)
        fRot = FloatArray(count) { -999f }
        fLabels = (0 until count).mapNotNull { runCatching { flowLayer.addLabel(LabelOptions.from(pts[0]).setStyles(st).setVisible(false).setClickable(false)) }.getOrNull() }
        handler.post(flowTick)
    }

    // ---------- 핀 ----------
    private val focusLayer by lazy { lm.addLayer(LabelLayerOptions.from("focusedWarning").setZOrder(10200)
        .setCompetitionType(CompetitionType.None).setClickable(true))!! }
    private var focusedLabel: Label? = null
    private var focusedId: String? = null
    fun focusWarning(pin: Pin?) {
        if (focusedId == pin?.id) return
        focusedLabel?.let { focusLayer.remove(it) }
        focusedId = pin?.id
        focusedLabel = null
        if (pin != null) {
            val badge = bubbleImage(ctx, null, accent(3), 58, warning = true)
            focusedLabel = focusLayer.addLabel(LabelOptions.from(ll(pin.lat, pin.lon))
                .setStyles(labelStyles(badge, 0.5f, 20f / 52f)).setClickable(true).setTag(pin))
        }
        syncPins(refreshZoom = true)
    }


    private var pinsRef: List<Pin>? = null
    private var pinLabels: List<Label> = emptyList()
    private data class PinGroup(val pins: List<Pin>)
    private var ownLabels = emptyList<Label>()
    private var expectedPinLabels = 0
    private var ownHidden = false
    private var pinZoom = -1
    private var pinRotation = 0f
    private val singleStyles = mutableMapOf<Pin, LabelStyles>()
    private val groupStyles = mutableMapOf<Pair<Int, Boolean>, LabelStyles>()
    private val accent = { kind: Int -> when (kind) { 3 -> 0xFFFF4D4D.toInt(); 2 -> 0xFFFFFFFF.toInt(); else -> 0xFFB9FA03.toInt() } }
    private val postSizeDp = 48
    private val groupSizeDp = 52
    private val ownDot by lazy {
        val size = (14 * d).toInt()
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            val c = Canvas(it)
            val ink = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawCircle(size / 2f, size / 2f, size * 0.42f, ink)
            ink.color = Color.WHITE
            c.drawCircle(size / 2f, size / 2f, size * 0.32f, ink)
        }
    }
    val startupLabelsReady: Boolean
        get() = me != null && coursesRef === courseOv.courses && cls.all { it.lines.size == it.course.lines.count { line -> line.lat.size >= 2 } } && pinsRef === pinOv.pins && pinLabels.size == expectedPinLabels

    fun onPinZoomChanged() {
        if (pinZoom != k.zoomLevel || kotlin.math.abs((k.cameraPosition?.rotationAngle?.toFloat() ?: 0f) - pinRotation) > 0.01f) syncPins(refreshZoom = true)
    }

    private var lastBubbleKeys: Set<Set<String>> = emptySet()
    private var pinBounce: android.animation.ValueAnimator? = null

    private fun bouncePins(labels: List<Label>) {
        if (labels.isEmpty() || !android.animation.ValueAnimator.areAnimatorsEnabled()) return
        val offsets = labels.map { it.pixelOffset }
        pinBounce = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                val rise = (1f - progress).let { it * it * it } * 12f * d
                val overshoot = kotlin.math.sin(Math.PI * progress).toFloat() * 4f * d
                val scale = 1f - 0.16f * (1f - progress) + 0.10f * kotlin.math.sin(Math.PI * progress).toFloat()
                labels.forEachIndexed { i, label ->
                    label.changePixelOffset(offsets[i].x, offsets[i].y + rise - overshoot)
                    label.scaleTo(scale, scale)
                }
            }
            start()
        }
    }

    private fun syncPins(refreshZoom: Boolean = false) {
        val hideMine = courseOv.guiding || !courseOv.visible
        if (hideMine != ownHidden) {
            ownHidden = hideMine
            ownLabels.forEach { if (hideMine) it.hide() else it.show(false) }
        }
        val pins = pinOv.pins
        if (pins === pinsRef && !refreshZoom) return
        val scale = metersPerPixel()?.takeIf { it > 0 && it.isFinite() } ?: return
        if (pins !== pinsRef) singleStyles.keys.retainAll(pins.toSet())
        pinsRef = pins
        pinZoom = k.zoomLevel
        pinRotation = (k.cameraPosition?.rotationAngle?.toFloat() ?: 0f)
        pinBounce?.cancel()
        pinLabels.forEach { bubbles.remove(it) }
        val newKeys = mutableSetOf<Set<String>>()
        val appeared = mutableListOf<Label>()
        val labels = mutableListOf<Label>()
        val mine = mutableListOf<Label>()
        expectedPinLabels = 0
        fun add(pos: LatLng, style: LabelStyles, tag: Any, visible: Boolean = true): Label? {
            expectedPinLabels++
            return bubbles.addLabel(LabelOptions.from(pos).setStyles(style).setVisible(visible).setClickable(true).setTag(tag))
                ?.also { labels.add(it) }
        }
        val ownStyle = labelStyles(ownDot, 0.5f, 0.5f, EudMapView.OWN_DOT_ZOOM)
        for (p in pins.filter { it.kind == 2 }) {
            add(ll(p.lat, p.lon), ownStyle, p, !hideMine)?.let { mine.add(it) }
        }
        // Group by the footprint of a bubble on screen. Sorting keeps groups stable while panning.
        val remaining = pins.filter { it.kind != 2 && it.id != focusedId }.sortedWith(compareBy<Pin> { it.warn }.thenBy { it.id }).toMutableList()
        val radius = (groupSizeDp + 6) * d * scale
        val normalCenters = mutableListOf<android.graphics.Point>()
        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            val origin = GeoPoint(seed.lat, seed.lon)
            val group = mutableListOf(seed)
            val next = remaining.iterator()
            while (next.hasNext()) {
                val pin = next.next()
                if (pin.warn == seed.warn && origin.distanceToAsDouble(GeoPoint(pin.lat, pin.lon)) <= radius) {
                    group.add(pin)
                    next.remove()
                }
            }
            val pos = ll(group.map { it.lat }.average(), group.map { it.lon }.average())
            val tag: Any = if (group.size == 1) seed else PinGroup(group)
            val style = if (group.size == 1) singleStyles.getOrPut(seed) {
                labelStyles(bubbleImage(ctx, seed.icon, accent(seed.kind), postSizeDp, seed.warn))
            } else groupStyles.getOrPut(group.size to seed.warn) {
                labelStyles(bubbleImage(ctx, null, accent(if (seed.warn) 3 else 0), groupSizeDp, warning = seed.warn, count = group.size))
            }
            val label = add(pos, style, tag)
            val key = group.map { it.id }.toSet()
            val point = k.toScreenPoint(pos) ?: continue
            val screen = ctx.resources.displayMetrics
            if (point.x in 0..screen.widthPixels && point.y in 0..screen.heightPixels) {
                newKeys.add(key)
                if (label != null && key !in lastBubbleKeys) appeared.add(label)
            }
            if (!seed.warn) {
                normalCenters.add(point)
            } else {
                // Move only the drawn badge. Keep the members, coordinates and tap target together.
                val gap = (groupSizeDp + 4) * d
                val neighbors = normalCenters.filter { kotlin.math.abs(it.y - point.y) < groupSizeDp * 1.3f * d }
                if (neighbors.any { kotlin.math.abs(it.x - point.x) < gap }) {
                    val offsets = neighbors.flatMap { listOf(it.x - point.x + gap, it.x - point.x - gap) }
                    val offset = offsets.filter { dx -> neighbors.all { kotlin.math.abs(point.x + dx - it.x) >= gap - 0.1f } }
                        .minByOrNull { kotlin.math.abs(it) }
                    if (offset != null) label?.changePixelOffset(offset, 0f)
                }
            }
        }
        ownLabels = mine
        lastBubbleKeys = newKeys
        bouncePins(appeared)
        pinLabels = labels
    }

    // ---------- 내 위치 ----------
    private var me: Label? = null; private var meArrow: Label? = null
    private var meBmpRef: Bitmap? = null
    private val meStyles = mutableMapOf<Int, LabelStyles>()
    private var meOpacityStep = 8
    private var lastFadeUpdate = 0L

    private fun meStyle(step: Int): LabelStyles? {
        val bmp = meBmpRef ?: return null
        return meStyles.getOrPut(step) {
            val image = if (step == 8) bmp else Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawBitmap(bmp, 0f, 0f, Paint().apply { alpha = (128 + step * 127 / 8) })
            }
            labelStyles(image, 0.5f, 1f)
        }
    }

    fun refreshMeOpacity() {
        val label = me ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastFadeUpdate < 40) return
        lastFadeUpdate = now
        val scale = metersPerPixel()?.takeIf { it > 0 && it.isFinite() } ?: return
        val step = (((scale - 0.45) / 0.9).coerceIn(0.0, 1.0) * 8).toInt()
        if (step == meOpacityStep) return
        meStyle(step)?.let { label.setStyles(it); meOpacityStep = step }
    }
    fun updateMe(lat: Double, lon: Double, bmp: Bitmap?, nav: Boolean, bearing: Float) {
        if (bmp != null && bmp !== meBmpRef) {
            meBmpRef = bmp
            meStyles.clear()
            me?.let { runCatching { meLayer.remove(it) } }
            me = null
        }
        val styles = meStyle(meOpacityStep) ?: return
        val pos = ll(lat, lon)
        if (me == null) {
            me = runCatching { meLayer.addLabel(LabelOptions.from(pos).setStyles(styles).setClickable(false)) }.getOrNull()
        } else me?.moveTo(pos)
        if (nav) {
            if (meArrow == null) meArrow = runCatching {
                val a = Bitmap.createBitmap((34 * d).toInt(), (44 * d).toInt(), Bitmap.Config.ARGB_8888)
                val c = Canvas(a); val cx = a.width / 2f; val top = 2 * d
                val tri = Path().apply { moveTo(cx, top); lineTo(cx + 12 * d, top + 26 * d); lineTo(cx, top + 20 * d); lineTo(cx - 12 * d, top + 26 * d); close() }
                val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }; c.drawPath(tri, fp)
                fp.style = Paint.Style.STROKE; fp.strokeWidth = 2f * d; fp.color = 0xFF1E2615.toInt(); c.drawPath(tri, fp)
                meLayer.addLabel(LabelOptions.from(pos).setStyles(labelStyles(a, 0.5f, 0.5f)))
            }.getOrNull() else meArrow!!.moveTo(pos)
            meArrow?.rotateTo(Math.toRadians(bearing.toDouble()).toFloat()); meArrow?.show(false)
            me?.changePixelOffset(0f, 0f)
        } else meArrow?.hide()
    }

    // ---------- 어둡게 덮기(지도 안 반투명 검정 면) ----------
    private var dim: com.kakao.vectormap.shape.Polygon? = null
    private var dimLayer: com.kakao.vectormap.shape.ShapeLayer? = null
    fun setDim(on: Boolean, color: Int) {
        dim?.let { runCatching { dimLayer?.remove(it) } }; dim = null
        if (!on) return
        val pts = listOf(ll(33.0, 126.0), ll(33.0, 131.5), ll(38.7, 131.5), ll(38.7, 126.0))
        if (dimLayer == null) dimLayer = runCatching { k.shapeManager!!.addLayer(com.kakao.vectormap.shape.ShapeLayerOptions.from("dim", 100, com.kakao.vectormap.shape.ShapeLayerPass.Default)) }.getOrNull()
        dim = runCatching { dimLayer!!.addPolygon(com.kakao.vectormap.shape.PolygonOptions.from(com.kakao.vectormap.shape.MapPoints.fromLatLng(pts), com.kakao.vectormap.shape.PolygonStyles.from(color)).setZOrder(0)) }.getOrNull()
    }

    // ---------- 버블 놓기(+) ----------
    private var cone: Label? = null
    private var lastHd = -999f; private var lastHdPos = ""
    fun updateHeading(lat: Double, lon: Double, headingDeg: Float?) {
        if (headingDeg == null) { cone?.hide(); lastHd = -999f; return }
        val pk = "%.6f,%.6f".format(lat, lon)
        if (cone != null && pk == lastHdPos && Math.abs(headingDeg - lastHd) < 1.5f) return
        lastHd = headingDeg; lastHdPos = pk
        val pos = ll(lat, lon)
        if (cone == null) cone = runCatching {
            val s = (84 * d).toInt(); val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(b)
            val cx = s / 2f; val cy = s / 2f
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(cx, cy, s / 2f, intArrayOf(0x00FFFFFF, 0x6679A900), floatArrayOf(0.1f, 1f), Shader.TileMode.CLAMP) }
            c.drawArc(RectF(0f, 0f, s.toFloat(), s.toFloat()), -90f - 38f, 76f, true, p)   // 위쪽을 향한 부채꼴
            meLayer.addLabel(LabelOptions.from(pos).setStyles(labelStyles(b, 0.5f, 0.5f)).setClickable(false))
        }.getOrNull() else cone!!.moveTo(pos)
        cone?.rotateTo(Math.toRadians(headingDeg.toDouble()).toFloat()); cone?.show(false)
    }

    // ---------- 탭 ----------
    init {
        k.setOnLabelClickListener { _, _, label ->
            when (val t = label.tag) {
                is PinGroup -> { pinOv.onGroupTap?.invoke(t.pins); true }
                is Pin -> { onPinTap?.invoke(t); true }
                is Course -> { if (!courseOv.guiding) { courseOv.selected = t; onCourseTap?.invoke(t) }; true }
                else -> false
            }
        }
        k.setOnMapClickListener { _, _, point, _ ->
            if (onBackgroundTap?.invoke() != true) tapMap(point.x, point.y)
        }
    }
    /** 길을 눌렀으면 true(그 길 선택), 아니면 false. */
    private fun tapMap(x: Float, y: Float): Boolean {
        if (!courseOv.visible || courseOv.guiding) return false
        var best: Course? = null; var bd = 24f * d
        var sharedHit: SharedPath? = null
        if (courseOv.selected == null) {
            for (lane in lanes) {
                var previous: android.graphics.Point? = null
                for (point in lane.points) {
                    val next = k.toScreenPoint(point)
                    val a = previous
                    if (a != null && next != null) {
                        val dist = seg(x, y, a.x.toFloat(), a.y.toFloat(), next.x.toFloat(), next.y.toFloat())
                        if (dist < bd && dist <= 7.5f * d) {
                            bd = dist; best = lane.course; sharedHit = lane.shared
                        }
                    }
                    previous = next
                }
            }
        }
        for (c in cls) {
            if (courseOv.selected != null && courseOv.selected !== c.course) continue
            if (sharedHit?.courses?.contains(c.course) == true) continue
            for (l in c.course.lines) {
                var ax = 0f; var ay = 0f
                for (i in l.lat.indices) {
                    val p = k.toScreenPoint(ll(l.lat[i], l.lon[i])) ?: continue
                    if (i > 0) { val dist = seg(x, y, ax, ay, p.x.toFloat(), p.y.toFloat()); if (dist < bd) { bd = dist; best = c.course } }
                    ax = p.x.toFloat(); ay = p.y.toFloat()
                }
            }
        }
        val c = best ?: return false
        courseOv.selected = c; onCourseTap?.invoke(c); return true
    }
    private fun seg(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val t = if (l2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** 매 프레임(가벼움): 데이터가 바뀌었을 때만 지도 안 요소를 다시 만든다. */
    fun sync() { syncCourses(); syncSlope(); syncSignals(); syncPins() }
}

/** 두 표식을 같은 크기로 그려 지도와 고도 그래프에서 같은 뜻으로 읽히게 한다. */
fun facilityIcon(d: Float, signal: Boolean): Bitmap {
    val size = (19 * d).toInt().coerceAtLeast(1)
    val image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(image); c.scale(size / 19f, size / 19f)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = Color.WHITE; c.drawRoundRect(0f, 0f, 19f, 19f, 5f, 5f, p)
    p.color = 0xFF232831.toInt(); c.drawRoundRect(1f, 1f, 18f, 18f, 4f, 4f, p)
    if (signal) {
        intArrayOf(0xFFFF7274.toInt(), 0xFFFFD16A.toInt(), 0xFF73DF95.toInt()).forEachIndexed { i, col ->
            p.color = col; c.drawCircle(9.5f, 4.7f + i*4.8f, 1.9f, p)
        }
    } else {
        p.color = Color.WHITE; c.rotate(12f, 9.5f, 9.5f)
        for (i in 0..2) c.drawRect(4f+i*4, 5f, 6.5f+i*4, 14f, p)
    }
    return image
}
