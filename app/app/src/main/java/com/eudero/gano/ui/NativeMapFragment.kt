package com.eudero.gano.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.eudero.gano.R
import com.eudero.gano.data.*
import com.eudero.gano.run.GuideNotifier
import com.eudero.gano.run.RunService
import com.eudero.gano.run.RunState
import com.eudero.maplib.Course
import com.eudero.maplib.EudMapView
import com.eudero.maplib.Pin
import com.eudero.maplib.GeoPoint
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

/** 지도: 부울경 모든 달리기 길을 한꺼번에. 길을 누르면 카드, "이 길로 가기"(해운대만)로 길 안내. */
class NativeMapFragment : Fragment() {
    private var map: EudMapView? = null
    private lateinit var loading: FrameLayout
    private lateinit var loadingText: TextView
    private lateinit var card: LinearLayout
    private var plan: Plan? = null
    private var curDir: Dir? = null
    private var nav: NavHelper? = null
    private var approachNav: NavHelper? = null
    private var activeApproach = emptyList<GeoPoint>()
    private var connector: GeoPoint? = null
    private var lastRouteCheck: GeoPoint? = null
    private var routeCheckJob: kotlinx.coroutines.Job? = null
    private var navOn = false
    private var guideCover = 0
    private var guideRequest = 0
    private var planning = false
    private var previewPlan: kotlinx.coroutines.Deferred<Plan?>? = null
    private data class CourseCard(val profile: CourseProfile, val photos: List<Bitmap>, val cover: Boolean)
    private var courseCards = emptyMap<Course, CourseCard>()
    private var demoPlan: Plan? = null
    private val initialPlans = mutableMapOf<Course, Plan>()
    private var previewHint: TextView? = null
    private var previewOrigin: GeoPoint? = null
    private lateinit var banner: TextView
    private lateinit var mapStatus: TextView
    private var healthSteps: Long? = null
    private var stepWindow = -1L
    private var mapControlsShown = true
    private lateinit var addBubble: Button
    private var summaryDlg: AlertDialog? = null
    private var postDlg: AlertDialog? = null
    private val runListener: () -> Unit = { onRunChanged() }
    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private var allPins = emptyList<Pin>()
    private var warningPaths: List<List<GeoPoint>>? = null
    private fun updateVisiblePins() {
        val paths = warningPaths
        map?.pinOverlay?.pins = if (paths == null) allPins else allPins.filter { pin ->
            pin.warn && paths.any { path -> nearGuide(GeoPoint(pin.lat, pin.lon), path, 100.0) }
        }.map { pin -> if (pin.kind == 3) pin else Pin(pin.id, pin.lat, pin.lon, true, pin.payload, 3, null) }
    }

    private fun showWarnings(paths: List<List<GeoPoint>>?) {
        warningPaths = paths
        updateVisiblePins()
    }

    private var displayedPosts: List<Post>? = null
    private val avatarCache = HashMap<String, Bitmap?>()
    private val locPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val m = EudMapView(ctx); map = m
        m.setStart(Areas.current.lat, Areas.current.lon, 14.0)
        m.setMeMarker(Avatar.bitmap(ctx)); m.bottomInsetDp = 74f
        fun ico(id: Int) = android.graphics.BitmapFactory.decodeResource(resources, id)
        m.setRecenterIcon(R.drawable.ic_map_target); m.setWheelIcons(ico(R.drawable.ic_g_write), ico(R.drawable.ic_g_photo), ico(R.drawable.ic_g_report))
        m.onMenuAction = { action -> openBubbleComposer(m, action) }
        m.courseOverlay.onCourseTap = { course ->
            if (!RunState.running) {
                if (plan != null) {
                    (parentFragment as? MapHostFragment)?.hideGuide(false)
                    closeCourse()
                }
                showCourse(course)
            }
        }
        m.onLocationFix = { refreshEntry(it) }
        m.onMapTap = {
            val dismiss = !RunState.running && m.courseOverlay.selected != null
            if (dismiss) {
                (parentFragment as? MapHostFragment)?.hideGuide(true)
                closeCourse()
            }
            dismiss
        }
        m.pinOverlay.onPinTap = { pin -> (pin.payload as? Post)?.let { showPost(it) } }

        m.pinOverlay.onGroupTap = { pins ->
            if (!m.zoomToGroup(pins)) {
                val posts = pins.mapNotNull { it.payload as? Post }.sortedByDescending { it.created }
                val titles = posts.map { (if (it.warn) "⚠ " else "") + it.text.lineSequence().first().take(35) }
                val dialog = AlertDialog.Builder(ctx).setTitle("이곳의 버블 ${posts.size}개")
                    .setItems(titles.toTypedArray()) { _, index -> showPost(posts[index]) }.create()
                dialog.show()
                Glass.dialog(dialog)
            }
        }

        card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16)); visibility = View.GONE; elevation = 12 * d; Glass.apply(this, 22f, 0xD0) }
        // 처음 로딩: 형광 연두 + 가운데 아이콘 + 정보를 불러오는 중 (n/4)
        loadingText = TextView(ctx).apply { textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Fonts.bold }
        loading = FrameLayout(ctx).apply {
            setBackgroundColor(0xFF111418.toInt()); isClickable = true; elevation = dp(24).toFloat()
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.brand_logo)
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(28).toFloat()
                    setColor(ctx.getColor(R.color.brand_accent))
                }
            }, LinearLayout.LayoutParams(dp(112), dp(112)))
            col.addView(ImageView(ctx).apply { setImageResource(R.drawable.wordmark); setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN); contentDescription = "뛰고보자"; scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(272), dp(90)).also { it.topMargin = dp(12) })
            col.addView(loadingText, LinearLayout.LayoutParams(-2, -2).also { it.topMargin = dp(18) })
            addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        val root = FrameLayout(ctx)
        root.addView(m, FrameLayout.LayoutParams(-1, -1))
        mapStatus = TextView(ctx).apply {
            textSize = 12f; typeface = Fonts.bold; setTextColor(0xFF121710.toInt()); gravity = Gravity.CENTER
            val steps = ctx.getDrawable(R.drawable.ic_map_footsteps)
            steps?.setBounds(0, 0, dp(22), dp(22))
            setCompoundDrawables(steps, null, null, null)
            compoundDrawablePadding = dp(3)
            setPadding(dp(8), 0, dp(8), 0)
            minWidth = dp(48)
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(EudMapView.ACCENT) }
            elevation = dp(4).toFloat()
            setOnClickListener { openSamsungHealth() }
        }
        root.addView(mapStatus, FrameLayout.LayoutParams(-2, dp(48), Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(13), 0, dp(13), dp(28))
        })
        updateMapStatus()
        root.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).also { it.setMargins(dp(12), 0, dp(12), dp(16)) })
        banner = TextView(ctx).apply {
            textSize = 17f; setTextColor(Color.WHITE); typeface = Fonts.bold; gravity = Gravity.CENTER; visibility = View.GONE
            setPadding(dp(18), dp(12), dp(18), dp(12)); Glass.apply(this, 18f, 0xE0)
        }
        root.addView(banner, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).also { it.setMargins(dp(14), dp(60), dp(14), 0) })
        addBubble = Button(ctx).apply {
            text = "+"; textSize = 28f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 0)
            contentDescription = "버블 추가"; setTextColor(0xFF121710.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(EudMapView.ACCENT) }
            elevation = dp(4).toFloat()
            setOnClickListener { openBubbleComposer(m, EudMapView.MenuAction.WRITE) }
        }
        root.addView(addBubble, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(13), dp(28))
        })
        root.addView(loading, FrameLayout.LayoutParams(-1, -1))
        load(false)
        return root
    }

    private fun updateMapStatus() {
        val running = RunState.running
        val key = if (running) RunState.startMs else 0L
        val count = healthSteps?.let { if (running && RunState.steps != null) RunState.steps else it.takeIf { stepWindow == key } }
        mapStatus.visibility = if (count != null && (mapControlsShown || navOn)) View.VISIBLE else View.GONE
        if (count != null) {
            mapStatus.text = "%,d".format(count)
            mapStatus.contentDescription = "걸음 수 ${mapStatus.text}, 삼성 헬스 열기"
        }
    }

    private fun openSamsungHealth() {
        val ctx = requireContext()
        val samsungHealth = "com.sec.android.app.shealth"
        val launch = ctx.packageManager.getLaunchIntentForPackage(samsungHealth)
            ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$samsungHealth"))
        try {
            startActivity(launch)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(ctx, "삼성 헬스를 열 수 없어요", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val running = RunState.running
                    val key = if (running) RunState.startMs else 0L
                    val from = if (running) java.time.Instant.ofEpochMilli(key)
                        else java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
                    healthSteps = runCatching { com.eudero.gano.health.HeartRate.steps(requireContext(), from) }.getOrNull()
                    stepWindow = key
                    updateMapStatus()
                    delay(15_000)
                }
            }
        }
    }

    private fun load(force: Boolean) {
        val ctx = context ?: return
        val m = map ?: return
        (activity as? MainActivity)?.setMapReady(false)
        loading.animate().cancel()
        loading.alpha = 1f; loading.visibility = View.VISIBLE
        loading.setOnClickListener(null); loading.isClickable = true
        loadingText.text = "정보를 불러오는 중 (0/4)"
        thread {
            RunStore.seedSamples(ctx)
            val coursesReady = runCatching { CourseRepo.loadAll(ctx, force) { n ->
                activity?.runOnUiThread { if (map === m) loadingText.text = "정보를 불러오는 중 ($n/4)" }
            } }.getOrDefault(false)
            activity?.runOnUiThread { if (map === m) loadingText.text = "내 버블과 주변 버블을 불러오는 중이에요" }
            val posts = runCatching { Repo.loadMapBubbles(ctx) }.getOrNull()
            activity?.runOnUiThread {
                if (map !== m) return@runOnUiThread
                when {
                    posts == null -> retry("버블을 불러오지 못했어요") { load(false) }
                    !coursesReady -> retry("코스를 불러오지 못했어요") { load(true) }
                    else -> { apply(posts); waitLocation(m) }
                }
            }
        }
    }

    private fun retry(message: String, again: () -> Unit) {
        loadingText.text = "$message\n화면을 눌러 다시 시도해 주세요"
        loading.setOnClickListener { loading.setOnClickListener(null); loading.isClickable = true; again() }
    }

    private fun prepareCards(ctx: android.content.Context, posts: List<Post>): Map<Course, CourseCard> {
        val photos = posts.filter { !it.warn && it.img.isNotEmpty() }
        val images = mutableMapOf<String, Bitmap?>()
        val covers = ctx.assets.list("courses").orEmpty().toSet()
        return CourseRepo.all.associateWith { course ->
            val cover = "${course.code}.jpg" in covers
            val paths = if (cover) listOf("courses/${course.code}.jpg") else if (course.detail) {
                photos.filter { it.road.isNotEmpty() && course.name.contains(it.road.take(2)) }
                    .ifEmpty { photos }.take(3).map { it.img }
            } else emptyList()
            val thumbs = paths.mapNotNull { path ->
                if (path !in images) images[path] = ImageLoader.thumbnail(ctx, path)
                images[path]
            }
            CourseCard(CourseProfile(CourseRepo.profileFor(course), CourseRepo.courseCrossings(course.code)), thumbs, cover)
        }
    }

    private fun waitLocation(m: EudMapView) {
        loadingText.text = "내 위치를 찾는 중이에요"
        m.whenLocated(25000) { ok ->
            if (map !== m) return@whenLocated
            if (ok) {
                val origin = currentPos(m)
                if (origin == null) {
                    retry("현재 위치를 찾지 못했어요") { waitLocation(m) }
                    return@whenLocated
                }
                loadingText.text = "코스 합류 경로와 A·B 방향을 준비하고 있어요"
                val ctx = requireContext().applicationContext
                val posts = displayedPosts.orEmpty()
                viewLifecycleOwner.lifecycleScope.launch {
                    val cards = async(Dispatchers.IO) { prepareCards(ctx, posts) }
                    val result = withContext(Dispatchers.Default) {
                        runCatching {
                            val plans = CourseRepo.courses.associateWith { course -> GuidePlanner.plan(course, origin) }
                            val demo = CourseRepo.courses.find { it.code == "HD-BEACHPROM" }?.let {
                                GuidePlanner.plan(it, demoHotel, demo = true)
                            }
                            plans to demo
                        }
                    }
                    val preparedCards = cards.await()
                    val prepared = result.getOrNull()?.first
                    if (map !== m) return@launch
                    if (prepared == null || prepared.values.any { it == null }) {
                        retry("일부 코스의 경로를 준비하지 못했어요") { load(true) }
                        return@launch
                    }
                    courseCards = preparedCards
                    demoPlan = result.getOrThrow().second
                    initialPlans.clear()
                    prepared.forEach { (course, guide) -> if (guide != null) initialPlans[course] = guide }
                    waitForBubbles(m)
                }
            }
            else if (!m.hasLocationPermission()) {
                retry("위치 권한이 필요해요") {
                    locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    waitLocation(m)
                }
            } else retry("현재 위치를 찾지 못했어요. GPS를 확인해 주세요") { waitLocation(m) }
        }
    }

    private fun waitForBubbles(m: EudMapView) {
        loadingText.text = "지도에 버블을 표시하고 있어요"
        val deadline = android.os.SystemClock.uptimeMillis() + 15000
        var framePending = false
        fun check() {
            if (map !== m) return
            when {
                android.os.SystemClock.uptimeMillis() >= deadline -> { framePending = false; retry("지도에 버블을 표시하지 못했어요") { load(false) } }
                else -> {
                    if (m.startupLabelsReady && !framePending) {
                        framePending = true
                        m.afterMapFrame {
                            if (map !== m || !framePending) return@afterMapFrame
                            framePending = false
                            if (!m.startupLabelsReady) return@afterMapFrame
                            loading.visibility = View.GONE
                            (activity as? MainActivity)?.setMapReady(true)
                        }
                    }
                    m.postDelayed({ if (loading.visibility == View.VISIBLE) check() }, 80)
                }
            }
        }
        check()
    }

    private fun avatarOf(ctx: android.content.Context, id: String): Bitmap? = avatarCache.getOrPut(id) {
        SampleData.avatar(ctx, SampleData.person(ctx, id))?.let { Bitmap.createScaledBitmap(it, 96, 96, true) }
    }

    private fun apply(snapshot: List<Post>? = null) {
        val m = map ?: return; val ctx = context ?: return
        val courses = CourseRepo.all
        if (m.courseOverlay.courses != courses) m.courseOverlay.courses = courses
        val posts = (snapshot ?: Repo.allPosts(ctx)) + demoPosts
        if (posts == displayedPosts) return
        displayedPosts = posts
        allPins = posts.mapNotNull { p ->
            val mine = p.local || p.authorId == "p1"
            val kind = if (mine) 2 else if (p.warn) 3 else 0   // 나=흰색, 경고=빨강, 다른 사람=연두
            val face = if (kind == 0) avatarOf(ctx, p.authorId) else null
            if (kind == 0 && face == null) return@mapNotNull null
            Pin(p.id, p.lat, p.lon, p.warn, p, kind, face)
        }
        updateVisiblePins()
    }

    /** 내 현재 위치: 달리는 중이면(시험 달리기 포함) 최근 기록 위치, 아니면 지도의 내 위치 */
    private fun currentPos(m: EudMapView): GeoPoint? =
        (if (RunState.running) RunState.points.lastOrNull()?.let { GeoPoint(it.lat, it.lon) } else null) ?: m.myLocation

    private fun openBubbleComposer(m: EudMapView, action: EudMapView.MenuAction) {
        val ctx = context ?: return
        val here = currentPos(m)
        if (here == null) {
            Toast.makeText(ctx, "내 위치를 찾는 중이에요", Toast.LENGTH_SHORT).show()
            return
        }
        val mode = if (action == EudMapView.MenuAction.WARN) "warn" else "person"
        val compose = Intent(ctx, PostActivity::class.java).putExtra("lat", here.latitude).putExtra("lon", here.longitude)
            .putExtra("mode", mode)
        if (RunState.demo && action == EudMapView.MenuAction.WRITE) {
            draftPoint = here
            demoWriter.launch(compose.putExtra("demoDraft", true))
        } else startActivity(compose)
    }

    private fun closeCourse() {
        previewPlan?.cancel(); previewPlan = null; previewOrigin = null
        previewHint = null
        showWarnings(null)
        guideRequest++
        routeCheckJob?.cancel(); activeApproach = emptyList(); connector = null
        planning = false
        plan = null; curDir = null; nav = null; approachNav = null; guideCover = 0
        map?.setRecenterVisible(true); mapControlsShown = true; addBubble.visibility = View.VISIBLE; updateMapStatus()
        GuideNotifier.hide(requireContext()); RunState.simPath = null
        map?.let { it.courseOverlay.selected = null; it.setLayers(emptyList(), emptyList(), false); it.clearGuide(); it.bottomInsetDp = 74f }
        card.animate().alpha(0f).translationY(dp(30).toFloat()).setDuration(180).withEndAction { card.visibility = View.GONE }.start()
    }

    /** 길을 누르면: 이름·소개·거리만. 오르막·신호등·횡단보도는 "이 길로 가기" 때 계산. */
    private fun showCourse(c: Course, animate: Boolean = true) {
        val m = map ?: return; val ctx = requireContext()
        m.courseOverlay.selected = c
        m.clearGuide()
        m.setRecenterVisible(false); mapControlsShown = false; addBubble.visibility = View.GONE; updateMapStatus()
        curDir = null
        val lines = c.lines.map { line -> line.lat.indices.map { GeoPoint(line.lat[it], line.lon[it]) } }
        showWarnings(lines)
        val main = lines.maxByOrNull { it.size }.orEmpty()
        m.showCourseArrows(main)
        val cardData = courseCards[c]
        m.setLayers(cardData?.profile?.steep.orEmpty(), cardData?.profile?.markers.orEmpty().map { it.dot }, true)
        card.post {
            if ((!c.detail || previewPlan == null) && m.courseOverlay.selected === c && card.isShown && curDir == null) {
                val cover = (m.height - card.top).coerceAtLeast(0)
                m.frameSelection(main + listOfNotNull(currentPos(m)), cover, animate)
            }
        }
        guideRequest++
        planning = false
        previewPlan?.cancel(); previewPlan = null; previewOrigin = null
        card.removeAllViews()
        card.addView(Glass.backButton(ctx) { closeCourse() })
        if (cardData != null && cardData.photos.isNotEmpty()) {
            val strip = LinearLayout(ctx)
            for (bitmap in cardData.photos) strip.addView(ImageView(ctx).apply {
                setImageBitmap(bitmap); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                background = GradientDrawable().apply { cornerRadius = 12 * d; setColor(0xFF1B1F26.toInt()) }
            }, LinearLayout.LayoutParams(0, dp(if (cardData.cover) 120 else 78), 1f).also { it.rightMargin = dp(4) })
            card.addView(strip, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) })
        }
        card.addView(TextView(ctx).apply { text = c.name; textSize = 19f; setTextColor(Color.WHITE); typeface = Fonts.bold })
        card.addView(TextView(ctx).apply { text = "${c.region} · %.1f km".format(c.lengthM / 1000.0); textSize = 13f; setTextColor(EudMapView.ACCENT); setPadding(0, dp(2), 0, dp(4)) })
        if (c.detail) card.addView(TextView(ctx).apply {
            text = "코스 전체  ·  " + CourseProfile.crossingSummary(CourseRepo.courseCrossings(c.code))
            textSize = 13f; setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(4))
        })
        card.addView(TextView(ctx).apply { text = c.tagline; textSize = 14f; setLineSpacing(0f, 1.18f); setTextColor(0xFFD5D9E0.toInt()); setPadding(0, 0, 0, dp(12)) })
        previewHint = if (c.detail) TextView(ctx).apply {
            text = "내 위치를 기다리는 중이에요"; textSize = 13f; setTextColor(EudMapView.ACCENT)
            setPadding(0, 0, 0, dp(8)); card.addView(this)
        } else null
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(ctx).apply {
            text = if (c.detail) "이 길로 가기" else "이 길로 가기 (준비 중)"; textSize = 15f
            setTextColor(if (c.detail) 0xFF0D0F13.toInt() else 0xFFB8BEC8.toInt())
            background = GradientDrawable().apply { cornerRadius = 14 * d; setColor(if (c.detail) EudMapView.ACCENT else 0x33FFFFFF) }
            setOnClickListener { if (c.detail) goTo(c) else notReady(c) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).also { it.rightMargin = dp(8) })
        card.addView(row)
        card.animate().cancel()
        if (c.detail) currentPos(m)?.let { preparePreview(c, it) }
        if (!animate) { card.visibility = View.VISIBLE; card.alpha = 1f; card.translationY = 0f; return }
        card.visibility = View.VISIBLE; card.alpha = 0f; card.translationY = dp(40).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(260).start()
    }

    private fun preparePreview(c: Course, origin: GeoPoint) {
        val m = map ?: return
        val hint = previewHint ?: return
        if (planning || plan != null) return
        val demo = RunState.demo
        val cached = (if (demo) demoPlan else initialPlans[c])?.takeIf { it.course === c && it.origin.distanceToAsDouble(origin) < 8 }
        previewPlan?.cancel()
        previewOrigin = origin
        val job = if (cached != null) kotlinx.coroutines.CompletableDeferred(cached)
            else viewLifecycleOwner.lifecycleScope.async(Dispatchers.Default) { GuidePlanner.plan(c, origin, demo) }
        previewPlan = job
        if (cached == null) hint.text = "현재 위치에서 합류점을 찾고 있어요"
        viewLifecycleOwner.lifecycleScope.launch {
            val guide = job.await()
            if (previewPlan !== job || map !== m || m.courseOverlay.selected !== c || plan != null) return@launch
            if (guide == null) {
                hint.text = "경로를 준비하지 못했어요. 이 길로 가기를 눌러 다시 시도해 주세요."
                previewPlan = null
                return@launch
            }
            if (!demo) initialPlans[c] = guide
            val path = guide.dirs.first().approach
            val joined = guide.isAtEntry(origin)
            hint.text = when {
                joined -> "코스 위에 있어요 · 바로 시작할 수 있어요"
                path.isNotEmpty() && guide.connectorTarget != null -> "점선은 보행로 입구 방향 · 파란 보행로 ${guide.approachM}m"
                path.isNotEmpty() -> "파란 길을 따라 합류점까지 도보 ${guide.approachM}m"
                else -> "점선은 합류점 방향 · 카카오맵 도보 길찾기 ↗"
            }
            hint.setOnClickListener(if (!joined && path.isEmpty()) View.OnClickListener {
                openWalkingDirections(requireContext(), currentPos(m) ?: origin, guide.entry)
            } else null)
            m.previewJoin(path, guide.entry.takeUnless { joined })
            m.setApproachConnector(guide.connectorTarget)
            val lines = c.lines.map { line -> line.lat.indices.map { GeoPoint(line.lat[it], line.lon[it]) } }
            if (path.isEmpty()) m.showCourseArrows(lines.maxByOrNull { it.size }.orEmpty())
            showWarnings(lines + listOf(path))
            card.post {
                if (previewPlan === job && plan == null && card.isShown)
                    m.frameSelection(if (joined) lines.flatten() + origin else listOf(origin, guide.entry) + path, m.height - card.top, false)
            }
        }
    }

    /** 해운대 밖 지역: 길 안내·달리기는 막고, 쉬운 말로 안내 */
    private fun notReady(c: Course) {
        val ctx = requireContext()
        val dlg = AlertDialog.Builder(ctx).setTitle("아직 준비 중인 지역이에요")
            .setMessage("${c.region}의 '${c.name}'은 설명만 볼 수 있어요.\n길 안내와 달리기는 해운대 길부터 열어뒀어요. 곧 다른 지역도 열릴 거예요!")
            .setPositiveButton("확인", null).create()
        dlg.show(); Glass.dialog(dlg)
    }

    /** 이 길로 가기: 레이어 켜기 + 합류길 진입 + 방향 A/B + 정보 계산 (해운대만) */
    private fun goTo(c: Course) {
        val m = map ?: return; val ctx = requireContext()
        if (!ensureLocationPermission()) return
        val me = currentPos(m) ?: run {
            m.enableMyLocation()
            Toast.makeText(ctx, "내 위치를 찾는 중이에요. 위치가 표시되면 다시 눌러 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (planning) return
        planning = true
        val request = ++guideRequest
        val prepared = previewPlan?.takeIf { (previewOrigin?.distanceToAsDouble(me) ?: Double.MAX_VALUE) < 8 }
        val demo = RunState.demo
        viewLifecycleOwner.lifecycleScope.launch {
            val pl = if (prepared != null) prepared.await() else withContext(Dispatchers.Default) {
                runCatching { GuidePlanner.plan(c, me, demo) }.getOrNull()
            }
            if (map !== m || request != guideRequest) return@launch
            planning = false
            if (!card.isShown || m.courseOverlay.selected !== c) return@launch
            if (pl == null) {
                Toast.makeText(ctx, "길을 불러오지 못했어요. 다시 눌러 주세요.", Toast.LENGTH_LONG).show()
                return@launch
            }
            routeCheckJob?.cancel()
            lastRouteCheck = pl.origin
            activeApproach = pl.dirs.first().approach
            connector = pl.connectorTarget
            plan = pl
            previewLayers(pl.dirs[0])   // 미리보기에서만: 오르막은 빨강, 주변 신호등
            card.visibility = View.GONE; m.bottomInsetDp = 330f
            guideCover = 0
            m.setFollowLock(false)
            (parentFragment as? MapHostFragment)?.showGuide(pl, {
                routeCheckJob?.cancel()
                plan = null; curDir = null; RunState.simPath = null
                GuideNotifier.hide(ctx)
                m.setLayers(emptyList(), emptyList(), false); m.clearGuide()
                showCourse(c)
            }, { dir ->
                curDir = dir
                nav = NavHelper(dir.coursePath)
                approachNav = activeApproach.takeIf { it.size >= 2 }?.let(::NavHelper)
                RunState.ascentM = dir.ascentM; previewLayers(dir); if (guideCover > 0) fitGuide()
                RunState.simPath = activeApproach.dropLast(1) + dir.coursePath; RunState.courseName = "${c.name} ${dir.label}"
                GuideNotifier.show(ctx, "${c.name} · ${dir.label}")
            }, { toggleRun() })
        }
    }

    /** 미리보기 전용: 가는 길의 오르막 구간(빨강)과 주변 신호등을 지도에 겹쳐 보여준다. 달리기가 시작되면 지운다. */
    private fun previewLayers(dir: Dir) {
        val m = map ?: return
        showWarnings(listOf(dir.path))
        m.setLayers(dir.profile.steep, dir.profile.markers.map { it.dot }, true)
    }

    /** 안내 창이 가리는 만큼 위쪽에 경로가 오도록 지도를 맞춘다 */
    fun updateGuideCover(px: Int) {
        val m = map ?: return
        guideCover = px; m.bottomInsetDp = px / d + 8f
        fitGuide()

    }
    private fun fitGuide() {
        val m = map ?: return; val dir = curDir ?: return
        if (navOn) return
        val guide = plan ?: return
        val here = currentPos(m) ?: guide.origin
        m.setApproachConnector(connector)
        m.setGuide(activeApproach, dir.coursePath, here, guideCover, guide.entry.takeUnless { guide.isAtEntry(here) })
    }

    /** 러닝이 끝나면 거리·시간·페이스·오르막·날짜와 달린 길 그림을 팝업으로 보여준다 */
    private fun showSummaryIfPending() {
        val id = RunState.pendingSummaryId; if (id == 0L || !isAdded) return
        RunState.pendingSummaryId = 0L
        val ctx = requireContext(); val r = RunStore.get(ctx, id) ?: return
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(16), dp(20), dp(8)) }
        box.addView(ImageView(ctx).apply { setImageBitmap(RouteIcon.render(r, 420)) }, LinearLayout.LayoutParams(dp(170), dp(170)))
        box.addView(TextView(ctx).apply { text = RunState.distStr(r.distanceM); textSize = 34f; setTextColor(EudMapView.ACCENT); typeface = Fonts.bold; setPadding(0, dp(8), 0, 0) })
        fun row(k: String, value: TextView) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(TextView(ctx).apply { text = k; textSize = 14f; setTextColor(0xFF9AA0A9.toInt()) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(value)
        }
        fun value(v: String) = TextView(ctx).apply {
            text = v; textSize = 15f; setTextColor(Color.WHITE); typeface = Fonts.bold
        }
        fun row(k: String, v: String) = row(k, value(v))
        val completed = RunState.completed
        val animateTime = completed && android.animation.ValueAnimator.areAnimatorsEnabled() && r.durationSec > 0
        val clock = value(RunState.timeStr(if (animateTime) 0 else r.durationSec)).apply {
            textSize = 26f
            setTextColor(EudMapView.ACCENT)
            fontFeatureSettings = "tnum"
        }
        box.addView(row("시간", clock), LinearLayout.LayoutParams(-1, -2))
        box.addView(row("평균 페이스", RunState.paceStr(r.durationSec, r.distanceM) + "/km"), LinearLayout.LayoutParams(-1, -2))
        box.addView(row("오르막", if (r.ascentM > 0) "+${r.ascentM}m" else "-"), LinearLayout.LayoutParams(-1, -2))
        box.addView(row("날짜", SimpleDateFormat("M월 d일 (E) a h:mm", Locale.KOREA).format(Date(r.startMs))), LinearLayout.LayoutParams(-1, -2))
        val effects = if (completed) FinishConfetti(ctx) else null
        val content = FrameLayout(ctx).apply {
            addView(box, FrameLayout.LayoutParams(-1, -2))
            effects?.let { addView(it, FrameLayout.LayoutParams(-1, -1)) }
        }
        val dlg = AlertDialog.Builder(ctx).setTitle(if (completed) "완주했어요!" else "달리기 끝!").setView(content)
            .setNeutralButton("공유하기", null).setPositiveButton("확인", null).create()
        summaryDlg = dlg; dlg.show(); Glass.dialog(dlg)
        effects?.burst()
        val timer = if (animateTime) android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1050
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            addUpdateListener { clock.text = RunState.timeStr((r.durationSec * (it.animatedValue as Float)).toLong()) }
            start()
        } else null
        dlg.setOnDismissListener { timer?.cancel(); if (summaryDlg === dlg) summaryDlg = null }
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { RouteIcon.shareResult(ctx, r) }
    }

    /** 달리기 시작/종료에 맞춰 내비게이션 모드 전환 */
    private fun onRunChanged() {
        val m = map ?: return
        updateMapStatus()
        if (RunState.running && plan != null && !navOn) {
            navOn = true; m.bottomInsetDp = 74f; updateMapStatus(); mapStatus.translationY = -dp(76).toFloat(); m.setNav(true); banner.visibility = View.VISIBLE
            curDir?.let(::previewLayers)
            (parentFragment as? MapHostFragment)?.enterNav()
        }
        if (navOn && RunState.running) {
            val pts = RunState.points
            val me = pts.lastOrNull()?.let { GeoPoint(it.lat, it.lon) } ?: m.myLocation ?: return
            val pending = RunState.pendingEntry
            if (plan?.isAtEntry(me) == true) approachNav = null
            val target = advanceConnector(me)
            val joining = approachNav
            if (pending != null && (joining == null || target != null)) retryApproach(me, pending)
            val directionOnly = target ?: pending?.takeIf { joining == null }
            val st = if (directionOnly == null) (joining ?: nav)?.step(me, allowArrival = joining == null) else null
            banner.text = when {
                directionOnly != null -> {
                    val place = if (activeApproach.isEmpty()) "합류점" else "보행로 입구"
                    "점선은 ${place} 방향\n${place}까지 직선 ${me.distanceToAsDouble(directionOnly).toInt()}m"
                }
                joining != null -> "코스 합류점으로 이동 중\n${st?.text.orEmpty()}"
                else -> st?.text.orEmpty() + RunState.remainStr().let { if (it.isNotEmpty()) "\n$it" else "" }
            }
            var bearing = directionOnly?.let { nav?.bearing(me, it)?.toFloat() } ?: st?.bearing ?: 0f
            if (pts.size >= 2) {
                val a = pts[pts.size - 2]
                val previous = GeoPoint(a.lat, a.lon)
                if (previous.distanceToAsDouble(me) >= 6.0) bearing = nav?.bearing(previous, me)?.toFloat() ?: bearing
            }
            m.navTo(me, bearing)
        }
        if (navOn && !RunState.running) {   // 종료: 원래 지도 모드로
            if (RunState.demo) RunState.points.lastOrNull()?.let { m.demoLocation = GeoPoint(it.lat, it.lon) }
            navOn = false; mapStatus.translationY = 0f; m.setNav(false); banner.visibility = View.GONE
            (parentFragment as? MapHostFragment)?.hideGuide(true); closeCourse()
            showSummaryIfPending()
        }
    }

    private fun advanceConnector(position: GeoPoint): GeoPoint? {
        val target = connector ?: return null
        if (plan?.isAtEntry(position) == true ||
            (activeApproach.isNotEmpty() && (position.distanceToAsDouble(target) <= 10 || nearGuide(position, activeApproach, 8.0)))) {
            connector = null
            map?.setApproachConnector(null)
        }
        return connector
    }

    private fun retryApproach(position: GeoPoint, entry: GeoPoint) {
        val guide = plan ?: return
        if (routeCheckJob?.isActive == true || (lastRouteCheck?.distanceToAsDouble(position) ?: 100.0) < 30) return
        lastRouteCheck = position
        routeCheckJob = viewLifecycleOwner.lifecycleScope.launch {
            val path = withContext(Dispatchers.Default) {
                val roads = CourseRepo.approachRoads.ifEmpty { CourseRepo.walkingRoads }
                Router.approach(roads, position, listOf(entry), alternateM = 15.0)?.points
            }?.takeIf { it.size >= 2 } ?: return@launch
            val dir = curDir ?: return@launch
            if (plan !== guide || (RunState.running && RunState.pendingEntry == null)) return@launch
            val here = map?.let(::currentPos) ?: position
            if (guide.isAtEntry(here) || here.distanceToAsDouble(position) > 30) return@launch
            activeApproach = path
            connector = null
            approachNav = NavHelper(path)
            map?.setApproachConnector(null)
            map?.updateApproach(path, dir.coursePath)
            if (!RunState.running) RunState.simPath = path.dropLast(1) + dir.coursePath
            (parentFragment as? MapHostFragment)?.updateApproach(approachNav, null, here)
            showWarnings(listOf(path, dir.coursePath))
        }
    }

    private fun refreshEntry(position: GeoPoint) {
        val guide = plan
        if (guide == null) {
            val selected = map?.courseOverlay?.selected ?: return
            val previous = previewOrigin
            if (selected.detail && !planning && previewPlan?.isActive != true &&
                (previous == null || previous.distanceToAsDouble(position) >= 20)) preparePreview(selected, position)
            return
        }
        if (!RunState.running) {
            val target = advanceConnector(position)
            (parentFragment as? MapHostFragment)?.updateApproach(approachNav, target, position)
            if (!guide.isAtEntry(position) && (activeApproach.isEmpty() || target != null ||
                    !nearGuide(position, activeApproach, 35.0))) retryApproach(position, guide.entry)
        }
    }

    private fun ensureLocationPermission(): Boolean {
        if (requireContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) return true
        locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
        return false
    }

    private fun toggleRun() {
        val ctx = requireContext()
        if (RunState.running) { RunService.stop(ctx); return }
        if (!ensureLocationPermission()) return
        val guide = plan ?: return
        val position = map?.let(::currentPos) ?: run {
            Toast.makeText(ctx, "내 위치를 찾는 중이에요", Toast.LENGTH_SHORT).show()
            return
        }
        advanceConnector(position)
        RunState.selectedCourse = curDir?.coursePath.orEmpty()
        RunState.pendingEntry = guide.entry.takeUnless { guide.isAtEntry(position) || RunState.demo }
        if (guide.isAtEntry(position)) {
            approachNav = null
            RunState.simPath = curDir?.coursePath
        }
        RunService.start(ctx)
    }

    /** 내 핀을 누르면: 프로필 사진과 이름 "나" */

    // ---- 시연 모드가 부르는 동작들 (사람이 누르는 것과 같은 흐름을 그대로 탄다) ----
    private var demoPosts = emptyList<Post>()
    private val demoHotel = GeoPoint(35.160951, 129.167202)
    private var demoCourse: Course? = null
    private var draftPoint: GeoPoint? = null
    private val demoWriter = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val point = draftPoint
        val text = result.data?.getStringExtra("text")
        if (RunState.demo && point != null && result.resultCode == android.app.Activity.RESULT_OK && text != null) {
            demoPosts = demoPosts + Post("demo-draft", point.latitude, point.longitude, "person", demoCourse?.name.orEmpty(),
                "나", text, "samples/photo_11.jpg", System.currentTimeMillis(), authorId = "p1")
            apply()
        }
    }
    private var demoWarning: Post? = null

    fun demoPrepare(): Boolean {
        val m = map ?: return false
        val hotel = demoHotel
        val eligible = CourseRepo.courses.filter { CourseRepo.profileFor(it).size >= 2 }
        demoCourse = eligible.firstOrNull { it.code == "HD-BEACHPROM" } ?: eligible.minByOrNull { c ->
            CourseRepo.profileFor(c).minOf { hotel.distanceToAsDouble(GeoPoint(it.lat, it.lon)) }
        } ?: return false
        (parentFragment as? MapHostFragment)?.hideGuide(false)
        closeCourse()
        demoDismissPost()
        demoDismissSummary()
        m.demoLocation = hotel
        m.moveToPoint(hotel.latitude, hotel.longitude, 17, 0)
        return true
    }

    suspend fun demoCourseTouch() {
        val m = map ?: return
        val here = m.demoLocation ?: return
        val near = demoCourse?.let { Profiles.get(requireContext(), it.code) }?.minByOrNull {
            here.distanceToAsDouble(GeoPoint(it.lat, it.lon))
        } ?: return
        m.screenPoint(near.lat, near.lon)?.let { m.demoTouch(it.x.toFloat(), it.y.toFloat()) }
    }
    fun demoSelect(c: Course? = demoCourse) {
        val m = map ?: return
        if (c == null) return
        m.courseOverlay.selected = c
        showCourse(c, animate = false)
    }

    class TourStop(val course: Course, val panel: LinearLayout, val overview: View, val elevation: View, val layers: CourseRepo.Layers)

    suspend fun prepareDemoTour(): List<TourStop> {
        val ctx = requireContext()
        val courses = CourseRepo.courses
        val slides = withContext(Dispatchers.IO) {
            val shots = Repo.allPosts(ctx).filter { !it.warn && it.img.startsWith("samples/") && it.road.isNotBlank() }
            val sampleRoads = mapOf(
                "HD-BEACHPROM" to "해운대 해변 산책로", "HD-BEACHPROM-EXT" to "해운대 해변 산책로 미포 연장",
                "HD-BEACHROAD" to "해운대해변로", "HD-DALMAJI" to "달맞이길", "HD-DONGBAEK" to "동백섬 순환 산책로",
                "HD-GALMAET" to "갈맷길", "HD-MOONTAN" to "달맞이 문탠로드", "HD-SONGJEONG" to "송정 해안",
                "HD-SUYEONG" to "수영강 자전거길"
            )
            val covers = ctx.assets.list("courses").orEmpty().toSet()
            val images = mutableMapOf<String, Bitmap?>()
            courses.map { c ->
                val file = "${c.code}.jpg"
                val path = if (file in covers) "courses/$file"
                    else shots.firstOrNull { it.road == sampleRoads[c.code] }?.img
                val image = path?.let { images.getOrPut(it) {
                    runCatching { ctx.assets.open(it).use { input ->
                        android.graphics.BitmapFactory.decodeStream(input, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })
                    } }.getOrNull()
                } }
                val profile = CourseProfile(CourseRepo.profileFor(c), CourseRepo.courseCrossings(c.code))
                val reds = c.lines.flatMap { line ->
                    CourseRepo.uphillOn(line.lat.indices.map { GeoPoint(line.lat[it], line.lon[it]) }, profile.steep)
                }
                Triple(c, image, profile to CourseRepo.Layers(reds, profile.markers.map { it.dot }))
            }
        }
        val chartWidth = (card.width - card.paddingLeft - card.paddingRight).takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - dp(60))
        fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
            text = value; textSize = size; setTextColor(color); if (bold) typeface = Fonts.bold
        }
        return slides.mapIndexed { index, (course, image, data) ->
            val (profile, layers) = data
            val points = profile.points
            yield()
            val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val heading = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            heading.addView(label(course.name, 19f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(label("${index + 1} / ${slides.size}", 14f, EudMapView.ACCENT, true))
            panel.addView(heading)
            panel.addView(label(course.tagline, 14f, 0xFFDBDFE4.toInt()).apply { setPadding(0, dp(4), 0, dp(12)) })
            val stage = FrameLayout(ctx)
            panel.addView(stage, LinearLayout.LayoutParams(-1, dp(142)))
            val overview = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            overview.addView(label("해운대  ·  코스 길이 %.1f km".format(course.lengthM / 1000.0), 15f, EudMapView.ACCENT, true))
            if (image != null) overview.addView(ImageView(ctx).apply {
                setImageBitmap(image); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xff202426.toInt()) }
            }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(7) })
            val elevation = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val low = points.minOfOrNull { it.second }?.toInt()
            val high = points.maxOfOrNull { it.second }?.toInt()
            elevation.addView(label(if (low != null && high != null) "경사 그래프  ·  고도 ${low}~${high} m" else "경사 정보를 준비 중이에요", 15f, EudMapView.ACCENT, true))
            if (points.size >= 3) {
                val h = dp(91)
                val chart = ProfileChart(ctx).apply { expandElevationRange = true; this.profile = profile; layout(0, 0, chartWidth, h) }
                val rendered = Bitmap.createBitmap(chartWidth, h, Bitmap.Config.ARGB_8888)
                chart.draw(android.graphics.Canvas(rendered))
                elevation.addView(ImageView(ctx).apply {
                    setImageBitmap(rendered); scaleType = ImageView.ScaleType.FIT_XY
                    contentDescription = "${course.name} 경사 그래프"
                }, LinearLayout.LayoutParams(-1, h))
            }
            elevation.addView(label("전체  " + CourseProfile.crossingSummary(CourseRepo.courseCrossings(course.code)) + "   ·   빨강  경사 10% 이상", 12f, Color.WHITE))
            stage.addView(overview); stage.addView(elevation)
            elevation.visibility = if (image == null) View.VISIBLE else View.GONE
            overview.visibility = if (image == null) View.GONE else View.VISIBLE
            TourStop(course, panel, overview, elevation, layers)
        }
    }

    fun demoTourStop(stop: TourStop) {
        val m = map ?: return
        val c = stop.course
        m.courseOverlay.selected = c
        m.setRecenterVisible(false); mapControlsShown = false; addBubble.visibility = View.GONE; updateMapStatus()
        val paths = c.lines.map { line -> line.lat.indices.map { GeoPoint(line.lat[it], line.lon[it]) } }
        showWarnings(paths)
        m.showCourseArrows(paths.maxByOrNull { it.size }.orEmpty())
        m.setLayers(stop.layers.segs, stop.layers.dots, true)
        m.bottomInsetDp = 246f
        m.fit(c.minLat, c.maxLat, c.minLon, c.maxLon, extraSouth = 0.8, animate = false)
        card.animate().cancel(); card.removeAllViews(); card.addView(stop.panel)
        card.alpha = 1f; card.translationY = 0f; card.visibility = View.VISIBLE
    }

    suspend fun demoGo() {
        (card.getChildAt(card.childCount - 1) as? LinearLayout)?.getChildAt(0)?.demoTouch()
        demoCourse?.let { goTo(it) }
    }
    fun demoGuideReady() = plan?.course === demoCourse && curDir != null && RunState.simPath != null
    suspend fun demoStartRun() {
        (parentFragment as? MapHostFragment)?.demoRunTouch()
        val start = map?.demoLocation ?: return
        map?.moveToPoint(start.latitude, start.longitude, 17, 0)
        map?.setFollowLock(true)
        RunService.startSim(requireContext())
    }
    fun demoResultId(): Long? = RunState.startMs.takeIf { RunState.completed && summaryDlg?.isShowing == true }
    suspend fun demoSummaryTouch() { summaryDlg?.getButton(AlertDialog.BUTTON_POSITIVE)?.demoTouch() }
    fun demoDismissSummary() { summaryDlg?.dismiss(); summaryDlg = null }

    val finishBubblesReady get() = isResumed && view?.isShown == true && demoWarning != null && map?.startupLabelsReady == true
    fun demoAtFinish() {
        val m = map ?: return
        val end = m.demoLocation ?: return
        m.moveToPoint(end.latitude, end.longitude, EudMapView.OWN_DOT_ZOOM, 0)
        val warning = Repo.allPosts(requireContext()).filter { !it.local && it.authorId != "p1" &&
            it.type in listOf("warn", "facility") && it.img.isNotEmpty()
        }.minByOrNull { end.distanceToAsDouble(GeoPoint(it.lat, it.lon)) }
            ?.takeIf { end.distanceToAsDouble(GeoPoint(it.lat, it.lon)) in 8.0..60.0 }
        demoWarning = warning ?: Post("demo-warning", end.latitude + 0.00007, end.longitude + 0.00019, "warn",
            demoCourse?.name.orEmpty(), "러너", "보도블록이 들떠 있어요. 발밑을 조심해 주세요.",
            "samples/accident_1.jpg", System.currentTimeMillis(), authorId = "p3")
        demoPosts = if (warning == null) listOfNotNull(demoWarning) else emptyList()
        draftPoint = GeoPoint(end.latitude - 0.00009, end.longitude - 0.00014)
        apply()
    }

    suspend fun demoCompose() {
        addBubble.demoTouch()
        val m = map ?: return
        openBubbleComposer(m, EudMapView.MenuAction.WRITE)
    }
    val draftPosted get() = demoPosts.any { it.id == "demo-draft" }
    private fun warningPin() = map?.pinOverlay?.pins?.firstOrNull { it.id == demoWarning?.id }
    fun demoFocusWarning() { map?.focusWarning(warningPin()) }
    val warningCentered get() = warningPin()?.let { map?.warningCentered(it) } == true
    suspend fun demoTapWarning() {
        val m = map ?: return
        val pin = warningPin() ?: return
        val hit = m.screenPoint(pin.lat, pin.lon) ?: return
        m.demoTouch(hit.x.toFloat(), hit.y.toFloat())
        val time = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(time, android.os.SystemClock.uptimeMillis(), action, hit.x.toFloat(), hit.y.toFloat(), 0)
            m.dispatchTouchEvent(event)
            event.recycle()
        }
    }
    val warningOpen get() = postDlg?.isShowing == true
    suspend fun demoPostTouch() { postDlg?.getButton(AlertDialog.BUTTON_POSITIVE)?.demoTouch() }
    fun demoDismissPost() { postDlg?.dismiss(); postDlg = null; map?.focusWarning(null) }
    fun demoEnd() {
        demoDismissPost()
        demoDismissSummary()
        val m = map ?: return
        (parentFragment as? MapHostFragment)?.hideGuide(false)
        closeCourse()
        m.demoLocation = null
        m.myLocation?.let { m.moveToPoint(it.latitude, it.longitude, 14, 0) }
        demoPosts = emptyList(); draftPoint = null; demoWarning = null; demoCourse = null
        apply()
    }

    private fun showPost(p: Post) {
        val ctx = requireContext()
        val who = SampleData.person(ctx, p.authorId)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)) }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val av = ProfilePhoto(ctx, if (p.warn) 0xFFFF4D4D.toInt() else if (p.local || p.authorId == "p1") Color.WHITE else EudMapView.ACCENT)
        (SampleData.avatar(ctx, who))?.let { av.setImageBitmap(it) }
        head.addView(av, LinearLayout.LayoutParams(dp(44), dp(44)))
        head.addView(TextView(ctx).apply {
            text = if (p.local || p.authorId == "p1") Avatar.displayName + " · 나" else who?.name ?: p.author
            textSize = 15f; setTextColor(Color.WHITE); typeface = Fonts.bold; setPadding(dp(12), 0, 0, 0) })
        head.contentDescription = "작성자 프로필 보기"
        head.setOnClickListener {
            if (p.local || p.authorId == "p1") Avatar.showProfile(ctx)
            else Avatar.showProfile(ctx, who, p.author, p.text.lineSequence().firstOrNull().orEmpty())
        }
        box.addView(head)
        if (p.img.isNotEmpty()) { val iv = ImageView(ctx).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }; box.addView(iv, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(12) }); ImageLoader.load(ctx, p.img, iv) }
        box.addView(TextView(ctx).apply {
            textSize = 15f; setPadding(0, dp(12), 0, 0); setTextColor(0xFFE6E9EE.toInt())
            text = p.text + "\n\n" + (if (p.road.isNotEmpty()) "${p.road} · " else "") + SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(p.created))
        })
        box.addView(Button(ctx).apply {
            isAllCaps = false
            fun updateLike() {
                val liked = BubbleLikes.liked(ctx, p.id)
                text = (if (liked) "♥" else "♡") + "  ${BubbleLikes.count(ctx, p)}"
                contentDescription = (if (liked) "좋아요 취소" else "좋아요") + " ${BubbleLikes.count(ctx, p)}개"
                setTextColor(if (liked) EudMapView.ACCENT else Color.WHITE)
                isSelected = liked
            }
            updateLike()
            setOnClickListener { BubbleLikes.toggle(ctx, p.id); updateLike() }
        }, LinearLayout.LayoutParams(-2, dp(48)).also { it.topMargin = dp(6) })
        val dlg = AlertDialog.Builder(ctx).setTitle(when (p.type) { "warn" -> "⚠ 경고"; "facility" -> "🛠 시설 신고"; "ask" -> "❓ 문의"; else -> "러너 이야기" }).setView(box).setPositiveButton("닫기", null).create()
        postDlg = dlg; dlg.show(); Glass.dialog(dlg)
    }

    override fun onResume() { RunState.listen(runListener); super.onResume(); if (!RunState.running) view?.post { showSummaryIfPending() }; map?.onResume(); map?.enableMyLocation(); if (loading.visibility == View.GONE) apply() }
    override fun onPause() { RunState.unlisten(runListener); map?.onPause(); super.onPause() }
    override fun onDestroyView() { map?.onDestroy(); map = null; displayedPosts = null; super.onDestroyView() }
}
