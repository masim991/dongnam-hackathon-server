package com.eudero.gano.ui

import android.app.Dialog
import android.content.Context
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.WindowManager

/** 앱 전체 글래스(유리) 스타일: 반투명 + 위쪽 밝은 빛 + 얇은 밝은 테두리. 팝업은 지원 기기에서 뒤 화면 블러. */
object Glass {
    fun backButton(ctx: Context, onBack: () -> Unit): ImageButton {
        val size = (48 * ctx.resources.displayMetrics.density).toInt()
        return ImageButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            setImageResource(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setColorFilter(Color.WHITE)
            background = bg(resources.displayMetrics.density, 24f, 0xB0)
            tag = "backButton"
            contentDescription = "뒤로가기"
            setOnClickListener { onBack() }
        }
    }

    fun detailScreen(activity: android.app.Activity, content: View): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            val pad = (12 * resources.displayMetrics.density).toInt()
            addView(backButton(activity) { activity.finish() }.also {
                (it.layoutParams as LinearLayout.LayoutParams).setMargins(pad, pad, 0, 0)
            })
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        }

    /** 반투명 유리 배경. radiusDp: 모서리, topOnly: 아래 모서리를 직각으로(하단 시트용) */
    fun bg(d: Float, radiusDp: Float = 20f, alpha: Int = 0xCC, topOnly: Boolean = false): GradientDrawable {
        val r = radiusDp * d
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            Color.argb(alpha, 0x2A, 0x30, 0x3A), Color.argb(alpha, 0x12, 0x15, 0x1A))).apply {
            if (topOnly) cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else cornerRadius = r
            setStroke((1.2f * d).toInt().coerceAtLeast(1), 0x44FFFFFF)
        }
    }

    fun apply(v: View, radiusDp: Float = 20f, alpha: Int = 0xCC, topOnly: Boolean = false) { v.background = bg(v.resources.displayMetrics.density, radiusDp, alpha, topOnly) }

    /** 팝업(AlertDialog 등)에 유리 배경 + (Android 12+) 뒤 화면 블러 */
    fun dialog(dlg: Dialog) {
        val w = dlg.window ?: return; val d = dlg.context.resources.displayMetrics.density
        w.setBackgroundDrawable(LayerDrawable(arrayOf(bg(d, 22f, 0xE6))).also { })
        if (Build.VERSION.SDK_INT >= 31) {
            w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            w.attributes = w.attributes.apply { blurBehindRadius = 28 }
            w.setBackgroundBlurRadius(40)
        }
        val content = w.findViewById<FrameLayout>(android.R.id.content)
        if (content != null) {
            val size = (48 * d).toInt()
            // Reserve a row inside the window, above the existing alert title or list.
            for (i in 0 until content.childCount) {
                val lp = content.getChildAt(i).layoutParams as FrameLayout.LayoutParams
                lp.topMargin += size
                content.getChildAt(i).layoutParams = lp
            }
            content.addView(backButton(dlg.context) { dlg.cancel() },
                FrameLayout.LayoutParams(size, size, android.view.Gravity.TOP or android.view.Gravity.START).apply {
                    marginStart = (8 * d).toInt()
                })
        }
        w.setDimAmount(0.35f)
    }
}
