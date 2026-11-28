package com.eudero.gano.ui

import android.content.Context
import android.graphics.*
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** 내 프로필: 받은 프로필 사진 중 하나(samples/person_1.png). 지도 핀에는 "나" 표시를 얹는다. */
object Avatar {
    const val displayName = "CV 팀"

    fun showProfile(ctx: Context, person: com.eudero.gano.data.Person? = com.eudero.gano.data.SampleData.person(ctx, "p1"),
                    author: String = displayName, quote: String = "") {
        val mine = person?.relation == "me"
        val name = if (mine) "$displayName (나)" else person?.name ?: author
        val bio = person?.bio?.takeIf { it.isNotBlank() } ?: quote

        val density = ctx.resources.displayMetrics.density
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val face = ProfilePhoto(ctx, 0x88FFFFFF.toInt()).apply {
            setImageBitmap(com.eudero.gano.data.SampleData.avatar(ctx, person))
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "$name 프로필 사진"
        }
        content.addView(face, LinearLayout.LayoutParams(1, 1))
        content.addView(TextView(ctx).apply {
            text = name
            gravity = Gravity.CENTER
            textSize = 20f
            typeface = Fonts.bold
            setTextColor(Color.WHITE)
            setPadding(0, (14 * density).toInt(), 0, (20 * density).toInt())
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(TextView(ctx).apply {
            text = bio; gravity = Gravity.CENTER; textSize = 15f; setTextColor(0xFFD5D9E0.toInt())
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), (18 * density).toInt())
        }, LinearLayout.LayoutParams(-1, -2))
        if (person == null) face.visibility = android.view.View.GONE
        content.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val side = ((right - left) * 0.8f).toInt()
            if (side > 0 && face.layoutParams.width != side) {
                face.layoutParams = LinearLayout.LayoutParams(side, side)
            }
        }
        val dlg = android.app.AlertDialog.Builder(ctx).setView(content).create()
        dlg.show()
        Glass.dialog(dlg)
    }

    fun photo(c: Context): Bitmap =
        runCatching { c.assets.open("samples/person_1.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
            ?: Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(0xFF9AA0A9.toInt()) }

    fun bitmap(c: Context): Bitmap {
        val base = photo(c).copy(Bitmap.Config.ARGB_8888, true); val cv = Canvas(base); val s = base.width.toFloat()
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
        cv.drawRoundRect(RectF(s * 0.14f, s * 0.64f, s * 0.86f, s * 0.94f), s * 0.15f, s * 0.15f, pill)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF14171C.toInt(); typeface = Fonts.bold; textSize = s * 0.22f; textAlign = Paint.Align.CENTER }
        cv.drawText("나", s / 2, s * 0.86f, p)
        return base
    }
}
