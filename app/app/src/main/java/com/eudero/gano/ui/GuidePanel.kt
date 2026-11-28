package com.eudero.gano.ui

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.*
import com.eudero.gano.data.Dir
import com.eudero.gano.data.Plan
import com.eudero.gano.data.Weather
import com.eudero.gano.run.RunState

internal fun openWalkingDirections(ctx: Context, here: com.eudero.maplib.GeoPoint, entry: com.eudero.maplib.GeoPoint) {
    val uri = android.net.Uri.parse("https://map.kakao.com/link/by/walk").buildUpon()
        .appendPath("내 위치,${here.latitude},${here.longitude}")
        .appendPath("합류점,${entry.latitude},${entry.longitude}").build()
    try {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
    } catch (_: android.content.ActivityNotFoundException) {
        Toast.makeText(ctx, "링크를 열 브라우저가 필요해요", Toast.LENGTH_SHORT).show()
    }
}

/** 길 안내 창(아래 창만 보임): 방향 A/B, 이 길의 정보, 선형 경사 그래프, 날씨 안내, 달리기 시작. */
class GuidePanel(ctx: Context, private val plan: Plan, private val onClose: () -> Unit, private val onDir: (Dir) -> Unit, private val onRun: () -> Unit) : LinearLayout(ctx) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private var dir: Dir = plan.dirs[0]
    private val title = TextView(ctx); private val sub = TextView(ctx); private val body = TextView(ctx); private val best = TextView(ctx)
    private val chart = ProfileChart(ctx); private val stats = TextView(ctx); private val goalText = TextView(ctx)
    val runButton: Button = Button(ctx).apply {
        textSize = 17f; typeface = Fonts.bold; setTextColor(0xFF0D0F13.toInt())
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xFFB9FA03.toInt()) }
        setOnClickListener { onRun() }
    }
    private val runBtn: Button get() = runButton
    private var here = plan.origin
    private var connector = plan.connectorTarget
    private var joinNav = plan.dirs.first().approach.takeIf { it.size >= 2 }?.let { com.eudero.gano.data.NavHelper(it) }
    private val fallback = TextView(ctx).apply {
        text = "카카오맵에서 합류점까지 걷기 ↗"
        setTextColor(0xFFB9FA03.toInt())
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setOnClickListener {
            openWalkingDirections(ctx, here, plan.entry)
        }
    }
    private val entryText = TextView(ctx).apply { textSize = 12.5f; setTextColor(0xFFB9FA03.toInt()) }
    /** 접힌 상태에서 보이는 높이: 머리글에서 코스 그래프까지 */
    var collapsedOffset = 0f
    var hiddenOffset = 0f      // 맨 아래로 내렸을 때(손잡이만 보임): 지도를 자세히 볼 수 있다
    var onSnap: ((Float) -> Unit)? = null
    fun snapTo(state: Int) { val t = when (state) { 0 -> 0f; 1 -> collapsedOffset; else -> hiddenOffset }; animate().translationY(t).setDuration(260).start(); onSnap?.invoke(t) }
    suspend fun demoPickDir(i: Int) { dirChips.getOrNull(i)?.let { it.demoTouch(); it.performClick() } }
    private var head: LinearLayout? = null
    fun collapsedVisiblePx(): Int = (head?.height ?: 0) + title.top - dp(10)
    private val handler = Handler(Looper.getMainLooper()); private var typing: Runnable? = null
    private val dirChips = ArrayList<TextView>()
    private val listener: () -> Unit = { refreshRun() }
    private val clockTick = object : Runnable {
        override fun run() { updateWeather(); handler.postDelayed(this, 60_000L) }
    }

    private fun chip(t: String) = TextView(context).apply { text = t; textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8)) }
    private fun paint(t: TextView, on: Boolean) {
        t.setTextColor(if (on) 0xFF0D0F13.toInt() else Color.WHITE)
        t.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(if (on) 0xFFB9FA03.toInt() else 0x33FFFFFF) }
    }

    init {
        orientation = VERTICAL
        background = android.graphics.drawable.LayerDrawable(arrayOf(Glass.bg(d, 22f, 0xF2, true), GridDrawable(d, 0x00000000, dp(22).toFloat(), false)))
        elevation = 16 * d; isClickable = true
        val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(8), dp(10), dp(4)) }
        this.head = head
        head.addView(Glass.backButton(ctx, onClose))
        head.addView(LinearLayout(ctx).apply {
            orientation = VERTICAL
            addView(TextView(ctx).apply { text = plan.course.name; textSize = 19f; setTextColor(Color.WHITE); typeface = Fonts.bold })
            addView(entryText)
            addView(fallback)
        }, LayoutParams(0, -2, 1f))
        addView(head)
        // 위로 끌어올리는 손잡이 + 머리글을 잡고 끌 수 있음
        val handle = View(ctx).apply { background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(0x66FFFFFF) } }
        addView(handle, LayoutParams(dp(44), dp(5)).also { it.topMargin = dp(8); it.gravity = Gravity.CENTER_HORIZONTAL })
        removeView(handle); addView(handle, 0, LayoutParams(dp(44), dp(5)).also { it.topMargin = dp(8); it.gravity = Gravity.CENTER_HORIZONTAL })
        var sy = 0f; var st = 0f
        val drag = OnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { sy = ev.rawY; st = translationY }
                android.view.MotionEvent.ACTION_MOVE -> translationY = (st + ev.rawY - sy).coerceIn(0f, hiddenOffset.coerceAtLeast(collapsedOffset))
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val stops = listOf(0f, collapsedOffset, hiddenOffset.coerceAtLeast(collapsedOffset))
                    val travel = ev.rawY - sy
                    val t = when {
                        travel < -dp(32) -> stops.lastOrNull { it < st - 1 } ?: 0f
                        travel > dp(32) -> stops.firstOrNull { it > st + 1 } ?: stops.last()
                        else -> stops.minByOrNull { Math.abs(it - translationY) } ?: 0f
                    }
                    animate().translationY(t).setDuration(220).start(); onSnap?.invoke(t)
                }
            }
            true
        }
        head.setOnTouchListener(drag); handle.setOnTouchListener(drag)

        val scroll = ScrollView(ctx); val col = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(dp(18), dp(4), dp(18), dp(16)) }
        val dirRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (plan.dirs.size > 1) plan.dirs.forEach { dd -> val c = chip(dd.label); dirChips.add(c); c.setOnClickListener { pick(dd) }; dirRow.addView(c, LayoutParams(-2, -2).also { it.rightMargin = dp(8) }) }
        else dirRow.addView(TextView(ctx).apply { text = dir.label; textSize = 13f; setTextColor(0xFF9AA0A9.toInt()) })
        dirRow.addView(View(ctx), LayoutParams(0, 1, 1f))
        val setBtn = chip("달리기 설정"); paint(setBtn, false); setBtn.setOnClickListener { openSettings() }
        dirRow.addView(setBtn)
        col.addView(dirRow, LayoutParams(-1, -2).also { it.bottomMargin = dp(8) })
        goalText.apply { textSize = 13f; setTextColor(0xFFD5D9E0.toInt()); visibility = GONE; setPadding(0, 0, 0, dp(6)) }; col.addView(goalText)
        stats.apply { textSize = 14f; setTextColor(0xFFB9FA03.toInt()); setPadding(0, 0, 0, dp(6)) }; col.addView(stats)
        col.addView(chart, LayoutParams(-1, dp(84)))
        col.addView(View(ctx), LayoutParams(-1, dp(14)))

        title.apply { textSize = 21f; setPadding(0, dp(8), 0, dp(2)) }
        sub.apply { textSize = 13f; setTextColor(0xFF9AA0A9.toInt()); setPadding(0, 0, 0, dp(10)) }
        body.apply { textSize = 15f; setTextColor(0xFFD5D9E0.toInt()); setLineSpacing(0f, 1.12f) }
        best.apply { textSize = 13f; setTextColor(0xFFB9FA03.toInt()); setPadding(0, dp(12), 0, 0) }
        col.addView(title); col.addView(sub); col.addView(body); col.addView(best)
        col.addView(TextView(ctx).apply { text = "예시 날씨를 바탕으로 안내해요. 출발 전 실제 예보도 확인해 주세요."; textSize = 11.5f; setTextColor(0xFF6E737C.toInt()); setPadding(0, dp(14), 0, 0) })
        scroll.addView(col); addView(scroll, LayoutParams(-1, 0, 1f))

        pick(dir); updateGoalText(); updateEntryPosition(plan.origin)
    }

    val currentDir: Dir get() = dir

    private fun pick(dd: Dir) {
        dir = dd; dirChips.forEachIndexed { i, c -> paint(c, plan.dirs[i] === dd) }
        chart.profile = dd.profile
        stats.text = "%.1f km  ·  오르막 +%dm\n선택 구간  ·  %s".format(dd.courseM / 1000.0, dd.ascentM, dd.crossingSummary)
        onDir(dd); updateWeather()
    }

    /** 달리기 설정: 거리/시간 중 하나만 고르고, 다이얼(휠)로 값과 페이스를 정한다 */
    private fun openSettings() {
        val ctx = context
        var type = RunState.goalType.takeIf { it in 1..3 } ?: 3
        val box = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(8)) }
        box.addView(TextView(ctx).apply { text = "목표 설정"; textSize = 20f; setTextColor(Color.WHITE); typeface = Fonts.bold })
        val tabs = LinearLayout(ctx).apply { orientation = HORIZONTAL; setPadding(0, dp(12), 0, dp(10)) }
        val tDist = chip("거리"); val tTime = chip("시간"); val tDone = chip("완주")
        tabs.addView(tDone, LayoutParams(0, -2, 1f).also { it.rightMargin = dp(8) }); tabs.addView(tDist, LayoutParams(0, -2, 1f).also { it.rightMargin = dp(8) }); tabs.addView(tTime, LayoutParams(0, -2, 1f))
        box.addView(tabs)
        fun picker(vals: Array<String>, idx: Int) = NumberPicker(ctx).apply {
            minValue = 0; maxValue = vals.size - 1; displayedValues = vals; value = idx.coerceIn(0, vals.size - 1); wrapSelectorWheel = false
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        }
        val kms = Array(84) { "%.1f".format(1.0 + it * 0.5) }                          // 1.0 ~ 42.5 km
        val mins = Array(36) { "${(it + 1) * 5}" }                                     // 5 ~ 180 분
        val distIdx = ((RunState.goalValue / 1000.0 - 1.0) / 0.5).toInt().let { if (RunState.goalType == 1) it else 8 }
        val timeIdx = (RunState.goalValue / 60 / 5 - 1).toInt().let { if (RunState.goalType == 2) it else 5 }
        val pDist = picker(kms, distIdx); val pTime = picker(mins, timeIdx)
        val pm = picker(Array(7) { "${it + 3}" }, RunState.paceSecPerKm / 60 - 3); val ps = picker(Array(12) { "%02d".format(it * 5) }, (RunState.paceSecPerKm % 60) / 5)
        fun lab(t: String) = TextView(ctx).apply { text = t; textSize = 13f; setTextColor(0xFF9AA0A9.toInt()); gravity = Gravity.CENTER }
        val gLabel = lab("")
        val goalCol = LinearLayout(ctx).apply { orientation = VERTICAL; gravity = Gravity.CENTER; addView(gLabel); addView(pDist); addView(pTime) }
        box.addView(goalCol, LayoutParams(-1, -2))
        box.addView(TextView(ctx).apply { text = "페이스 설정"; textSize = 20f; setTextColor(Color.WHITE); typeface = Fonts.bold; setPadding(0, dp(14), 0, dp(4)) })
        box.addView(lab("1km를 달리는 속도").apply { gravity = Gravity.START })
        box.addView(LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER; addView(pm); addView(lab("분")); addView(ps); addView(lab("초")) })
        fun refresh() {
            paint(tDist, type == 1); paint(tTime, type == 2); paint(tDone, type == 3)
            pDist.visibility = if (type == 1) VISIBLE else GONE; pTime.visibility = if (type == 2) VISIBLE else GONE
            gLabel.text = if (type == 1) "목표 거리 (km)" else if (type == 2) "목표 시간 (분)" else "선택한 길의 끝까지 달려요\n종점 30m 안이면 자동 완료"
        }
        tDist.setOnClickListener { type = 1; refresh() }; tTime.setOnClickListener { type = 2; refresh() }; tDone.setOnClickListener { type = 3; refresh() }
        refresh()
        val dlg = android.app.AlertDialog.Builder(ctx).setView(box)
            .setPositiveButton("확인") { _, _ ->
                RunState.goalType = type
                RunState.goalValue = if (type == 1) (1.0 + pDist.value * 0.5) * 1000.0 else if (type == 2) (pTime.value + 1) * 5 * 60.0 else 0.0
                RunState.paceSecPerKm = (pm.value + 3) * 60 + ps.value * 5
                updateGoalText()
            }.setNegativeButton("취소", null).create()
        dlg.show(); Glass.dialog(dlg)
    }
    private fun goalSummary(): String = (if (RunState.goalType == 1) "목표 %.1f km".format(RunState.goalValue / 1000.0) else if (RunState.goalType == 2) "목표 %d분".format((RunState.goalValue / 60).toInt()) else if (RunState.goalType == 3) "목표 완주" else "") +
        (if (RunState.goalType != 0) "  ·  " else "") + "페이스 %d'%02d\"".format(RunState.paceSecPerKm / 60, RunState.paceSecPerKm % 60)
    private fun updateGoalText() { if (RunState.goalType != 0) { goalText.text = goalSummary(); goalText.visibility = VISIBLE } }

    fun showDemoWeather() {
        updateWeather()
        val scroll = body.parent?.parent as? ScrollView ?: return
        scroll.post { scroll.smoothScrollTo(0, title.top) }
    }

    private var lastKey = ""
    private fun updateWeather() {
        val now = java.time.ZonedDateTime.now()
        val jogging = if (RunState.running) java.time.Instant.ofEpochMilli(RunState.startMs).atZone(now.zone) else now
        val date = jogging.toLocalDate()
        val hour = if (RunState.demo) 12 else jogging.hour
        val key = "$date-$hour-${now.toLocalDate()}-${RunState.demo}"
        if (key == lastKey) return
        lastKey = key
        val v = Weather.judge(date.isBefore(now.toLocalDate()), hour)
        val col = when (v.label) { "좋은" -> 0xFFB9FA03.toInt(); "무난한" -> Color.WHITE; "별로인" -> 0xFFFFB74D.toInt(); else -> 0xFFFF5252.toInt() }
        val t = SpannableString(v.lead + v.word + ".")
        t.setSpan(StyleSpan(android.graphics.Typeface.BOLD), v.lead.length, v.lead.length + v.word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        t.setSpan(ForegroundColorSpan(col), v.lead.length, v.lead.length + v.word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        title.setTextColor(Color.WHITE); title.text = t
        sub.text = (if (RunState.demo) "시연 예시 · " else "") + date.format(java.time.format.DateTimeFormatter.ofPattern("M월 d일 (E)", java.util.Locale.KOREA)) + " · " + v.subtitle; best.text = v.best + "."
        // AI가 글자를 써 내려가는 느낌의 타자 효과
        typing?.let { handler.removeCallbacks(it) }
        var n = 0; val full = v.body
        val r = object : Runnable { override fun run() { n = minOf(full.length, n + 2); body.text = full.substring(0, n); if (n < full.length) handler.postDelayed(this, 22) } }
        typing = r; body.text = ""; handler.post(r)
    }

    fun updateApproach(nav: com.eudero.gano.data.NavHelper?, target: com.eudero.maplib.GeoPoint?, position: com.eudero.maplib.GeoPoint) {
        joinNav = nav
        connector = target
        updateEntryPosition(position)
    }

    private fun updateEntryPosition(position: com.eudero.maplib.GeoPoint) {
        here = position
        val joined = plan.isAtEntry(position)
        val target = connector
        val navigation = joinNav
        fallback.visibility = if (!joined && joinNav == null) VISIBLE else GONE
        entryText.text = when {
            joined -> "코스에서 바로 시작할 수 있어요"
            navigation != null && target != null -> "점선은 보행로 입구 방향 · 직선 ${position.distanceToAsDouble(target).toInt()}m"
            navigation != null -> "합류점까지 파란 길 · ${navigation.step(position, false).text}"
            else -> "점선은 합류점 방향 · 직선 ${position.distanceToAsDouble(plan.entry).toInt()}m"
        }
        refreshRun()
    }

    private fun refreshRun() {
        runBtn.text = if (RunState.running) "달리기 종료  ·  ${RunState.timeStr()}  ${RunState.distStr()}" else if (RunState.demo || plan.isAtEntry(here)) "달리기 시작" else "합류 안내 시작"
        (runBtn.background as GradientDrawable).setColor(if (RunState.running) 0xFFFF5252.toInt() else 0xFFB9FA03.toInt())
        runBtn.setTextColor(if (RunState.running) Color.WHITE else 0xFF0D0F13.toInt())
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); RunState.listen(listener); handler.post(clockTick) }
    override fun onDetachedFromWindow() { RunState.unlisten(listener); handler.removeCallbacks(clockTick); typing?.let { handler.removeCallbacks(it) }; super.onDetachedFromWindow() }
}
