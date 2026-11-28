package com.eudero.gano.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.eudero.gano.R
import com.eudero.gano.data.Dir
import com.eudero.gano.data.Plan

/** 지도 탭: 지도 + 길 안내 창(위로 끌어올리는 시트) + 항상 보이는 달리기 버튼. */
class MapHostFragment : Fragment() {
    private lateinit var native: NativeMapFragment
    val nativeFrag get() = native
    private lateinit var root: FrameLayout
    private var guide: GuidePanel? = null
    private var btn: View? = null
    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val holder = FrameLayout(ctx).apply { id = R.id.map_container }
        native = (childFragmentManager.findFragmentByTag("native") as? NativeMapFragment) ?: NativeMapFragment()
        if (s == null) childFragmentManager.beginTransaction().add(R.id.map_container, native, "native").commit()
        root = FrameLayout(ctx)
        root.addView(holder, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    /** 길 안내 시작: 아래 창은 날짜·시간 선택까지만 보이고, 위로 끌어올리면 날씨 설명이 보인다. 지도는 창 위쪽 중앙에 경로가 오도록 맞춘다. */
    fun showGuide(plan: Plan, onClose: () -> Unit, onDir: (Dir) -> Unit, onRun: () -> Unit) {
        hideGuide(false)
        (activity as? MainActivity)?.setNavVisible(false)
        val ctx = requireContext()
        val btnArea = dp(72)
        val statusBar = androidx.core.view.ViewCompat.getRootWindowInsets(root)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())?.top ?: dp(24)
        val g = GuidePanel(ctx, plan, { hideGuide(true); onClose() }, onDir, onRun)
        guide = g
        root.addView(g, FrameLayout.LayoutParams(-1, -1).also { it.topMargin = statusBar; it.bottomMargin = btnArea })
        // 달리기 버튼 막대: 시트가 내려가도 계속 보이고, 아래로 밀려 내려간 시트 내용을 가려준다
        val bar = FrameLayout(ctx).apply { elevation = 24 * d; background = Glass.bg(d, 0f, 0xF6, true) }
        bar.addView(g.runButton, FrameLayout.LayoutParams(-1, dp(54), Gravity.CENTER).also { it.setMargins(dp(14), 0, dp(14), 0) })
        btn = bar; root.addView(bar, FrameLayout.LayoutParams(-1, btnArea, Gravity.BOTTOM))
        g.translationY = root.height.toFloat()
        g.post {
            val full = g.height
            val visible = g.collapsedVisiblePx().coerceAtMost(full)
            g.collapsedOffset = (full - visible).toFloat(); g.hiddenOffset = (full - dp(34)).toFloat()
            g.onSnap = { off -> native.updateGuideCover(((full - off).toInt()).coerceAtLeast(dp(34)) + btnArea) }
            g.translationY = g.collapsedOffset
            native.updateGuideCover(visible + btnArea)
        }
    }

    fun updateApproach(nav: com.eudero.gano.data.NavHelper?, target: com.eudero.maplib.GeoPoint?, position: com.eudero.maplib.GeoPoint) {
        guide?.updateApproach(nav, target, position)
    }

    /** 내비게이션 모드: 안내 창은 내리고 달리기 버튼만 남긴다 */
    fun enterNav() { guide?.let { g -> g.animate().translationY(g.height.toFloat()).setDuration(260).start() } }

    fun hideGuide(animate: Boolean) {
        guide?.let { g -> if (animate) g.animate().translationY(g.height.toFloat()).alpha(0f).setDuration(220).withEndAction { root.removeView(g) }.start() else root.removeView(g) }
        btn?.let { root.removeView(it) }
        guide = null; btn = null
        (activity as? MainActivity)?.setNavVisible(true)
    }


    fun showDemoWeather() {
        guide?.snapTo(0)
        guide?.showDemoWeather()
    }

    suspend fun demoRunTouch() { guide?.runButton?.demoTouch() }
    suspend fun demoPickDir(i: Int) { guide?.demoPickDir(i) }
    /** state: 0 펼침, 1 기본, 2 내림(지도 자세히) */
    fun demoSnapSheet(state: Int) { guide?.snapTo(state) }
}
