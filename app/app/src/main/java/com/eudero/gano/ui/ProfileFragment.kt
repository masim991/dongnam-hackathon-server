package com.eudero.gano.ui

import android.content.Intent
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.eudero.gano.R
import com.eudero.gano.health.HeartRate
import com.eudero.maplib.EudMapView
import kotlinx.coroutines.launch
import com.eudero.gano.BuildConfig
import com.eudero.gano.update.Updater

class ProfileFragment : Fragment() {
    private lateinit var scroll: ScrollView
    private lateinit var box: LinearLayout
    private lateinit var healthLabel: TextView

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        box = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        scroll = ScrollView(requireContext()).apply { isFillViewport = true; addView(box) }
        build()
        return scroll
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tile(parent: LinearLayout, label: String, icon: Int, onClick: () -> Unit): TextView {
        val ctx: Context = parent.context
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        val graphic = ImageView(ctx).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(26), dp(26), dp(26), dp(26))
            background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(EudMapView.ACCENT) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cell.addView(graphic, LinearLayout.LayoutParams(dp(100), dp(100)))
        val caption = TextView(ctx).apply {
            text = label; gravity = Gravity.CENTER; textSize = 14f; setTextColor(Color.WHITE); typeface = Fonts.bold
            setPadding(0, dp(8), 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cell.addView(caption, LinearLayout.LayoutParams(-1, -2))
        cell.contentDescription = label
        cell.setOnClickListener { onClick() }
        parent.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        return caption
    }

    fun refreshHealth() {
        if (view == null || !::healthLabel.isInitialized) return
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val linked = runCatching { HeartRate.hasConnection(ctx) }.getOrDefault(HeartRate.linked(ctx))
            val text = if (linked) "건강 연동됨" else "건강 앱 연동"
            healthLabel.text = text
            (healthLabel.parent as View).contentDescription = text
        }
    }

    override fun onResume() { super.onResume(); refreshHealth() }
    override fun onHiddenChanged(hidden: Boolean) { if (!hidden) refreshHealth() }

    private fun build() {
        box.removeAllViews()
        val ctx = requireContext()
        scroll.background = android.graphics.drawable.LayerDrawable(arrayOf(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF293411.toInt(), 0xFF0D0F13.toInt(), 0xFF1E2615.toInt())), GridDrawable(resources.displayMetrics.density, 0x00000000, 0f, false)))
        box.setPadding(dp(20), dp(72), dp(20), dp(14))

        val av = ProfilePhoto(ctx, EudMapView.ACCENT).apply {
            contentDescription = "CV 팀 프로필"
            setOnClickListener { Avatar.showProfile(ctx) }
            setImageBitmap(Avatar.photo(ctx))
        }
        box.addView(av, LinearLayout.LayoutParams(dp(104), dp(104)))
        box.addView(TextView(ctx).apply { text = Avatar.displayName; textSize = 24f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = com.eudero.gano.ui.Fonts.bold; setPadding(0, dp(12), 0, dp(2)) })
        box.addView(TextView(ctx).apply {
            text = com.eudero.gano.data.SampleData.person(ctx, "p1")?.bio
            gravity = Gravity.CENTER; textSize = 14f; setTextColor(Color.WHITE)
        })

        fun actionRow(topMargin: Int) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            box.addView(this, LinearLayout.LayoutParams(-1, -2).apply { this.topMargin = dp(topMargin) })
        }
        val first = actionRow(24)
        tile(first, "내가 쓴 버블", R.drawable.ic_profile_bubbles) { startActivity(Intent(ctx, MyBubblesActivity::class.java)) }
        healthLabel = tile(first, if (HeartRate.linked(ctx)) "건강 연동됨" else "건강 앱 연동", R.drawable.ic_profile_health) {
            (requireActivity() as MainActivity).requestHealthConnection()
        }
        val second = actionRow(16)
        tile(second, "안내 및 앱 정보", R.drawable.ic_profile_info) {
            startActivity(Intent(ctx, PrivacyActivity::class.java).putExtra("section", "all"))
        }
        tile(second, "업데이트 확인", R.drawable.ic_profile_update) { Updater.check(requireActivity(), true) }
        box.addView(Space(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        box.addView(TextView(ctx).apply { text = "버전 ${BuildConfig.VERSION_NAME}\n뛰고보자"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(0xFF6E737C.toInt()); setPadding(0, dp(18), 0, 0) })
    }
}
