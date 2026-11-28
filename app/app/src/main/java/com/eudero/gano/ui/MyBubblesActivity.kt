package com.eudero.gano.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.eudero.gano.data.ImageLoader
import com.eudero.gano.data.Post
import com.eudero.gano.data.Repo
import java.text.SimpleDateFormat
import java.util.*

/** 내가 쓴 버블(글·사진·위치) 목록. */
class MyBubblesActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val d = resources.displayMetrics.density; fun dp(v: Int) = (v * d).toInt()
        val posts = Repo.localPosts(this).sortedByDescending { it.created }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(8)) }
        root.addView(TextView(this).apply { text = "내가 쓴 버블 ${posts.size}"; textSize = 22f; setTextColor(Color.WHITE); typeface = com.eudero.gano.ui.Fonts.bold; setPadding(0, 0, 0, dp(12)) })
        val fmt = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
        if (posts.isEmpty()) root.addView(TextView(this).apply { text = "아직 쓴 글이 없어요\n지도를 꾹 눌러서 첫 버블을 남겨봐!"; textSize = 15f; setTextColor(0xFF8A8F98.toInt()); gravity = Gravity.CENTER; setPadding(0, dp(60), 0, 0) })
        val list = ListView(this).apply { divider = null; dividerHeight = 0 }
        list.adapter = object : BaseAdapter() {
            override fun getCount() = posts.size
            override fun getItem(p: Int) = posts[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup): View {
                val b = posts[p]
                val row = LinearLayout(this@MyBubblesActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(12), dp(14), dp(12))
                    background = GradientDrawable().apply { cornerRadius = 16 * d; setColor(0xFF14171C.toInt()) }
                }
                val iv = ImageView(this@MyBubblesActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = GradientDrawable().apply { cornerRadius = 12 * d; setColor(0xFF222831.toInt()) }; clipToOutline = true }
                if (b.img.isNotEmpty()) ImageLoader.load(this@MyBubblesActivity, b.img, iv)
                row.addView(iv, LinearLayout.LayoutParams(dp(76), dp(76)))
                val col = LinearLayout(this@MyBubblesActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
                col.addView(TextView(this@MyBubblesActivity).apply { text = (when (b.type) { "warn" -> "⚠ 경고"; "facility" -> "🛠 시설 신고"; "ask" -> "❓ 문의"; else -> "러너 이야기" }); textSize = 12f; setTextColor(0xFFB9FA03.toInt()) })
                col.addView(TextView(this@MyBubblesActivity).apply { text = b.text; textSize = 15f; setTextColor(Color.WHITE); maxLines = 2 })
                col.addView(TextView(this@MyBubblesActivity).apply { text = (if (b.road.isNotEmpty()) b.road + " · " else "") + fmt.format(Date(b.created)); textSize = 12f; setTextColor(0xFF8A8F98.toInt()) })
                row.addView(col)
                return FrameLayout(this@MyBubblesActivity).apply { setPadding(0, dp(5), 0, dp(5)); addView(row) }
            }
        }
        root.addView(list)
        setContentView(Glass.detailScreen(this, root))
    }
}
