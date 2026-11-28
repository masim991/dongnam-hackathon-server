package com.eudero.gano.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.LayoutAnimationController
import android.view.animation.TranslateAnimation
import android.widget.*
import androidx.fragment.app.Fragment
import com.eudero.gano.R
import com.eudero.gano.data.RouteIcon
import com.eudero.gano.data.RunStore
import com.eudero.gano.data.SavedRun
import com.eudero.gano.run.RunState
import com.eudero.maplib.EudMapView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

internal fun loadRecordPhoto(path: String, max: Int): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val size = info.size
        val scale = minOf(1f, max.toFloat() / maxOf(size.width, size.height))
        decoder.setTargetSize((size.width * scale).toInt().coerceAtLeast(1), (size.height * scale).toInt().coerceAtLeast(1))
    }
}.getOrNull()

/** Two views of the same records, opening the same run detail screen. */
class RecordFragment : Fragment() {
    private lateinit var list: ListView
    private lateinit var album: GridView
    private lateinit var empty: TextView
    private lateinit var listToggle: ImageButton
    private lateinit var albumToggle: ImageButton
    private var albumMode = false
    private var runs: List<SavedRun> = emptyList()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val date = SimpleDateFormat("M월 d일 (E) a h:mm", Locale.KOREA)
    private val shortDate = SimpleDateFormat("M월 d일 (E)", Locale.KOREA)

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        albumMode = ctx.getSharedPreferences("records_ui", Context.MODE_PRIVATE).getBoolean("album", false)
        list = ListView(ctx).apply { divider = null; dividerHeight = 0; clipToPadding = false }
        album = GridView(ctx).apply {
            numColumns = 2
            horizontalSpacing = dp(10)
            verticalSpacing = dp(10)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            clipToPadding = false
        }
        for (view in listOf(list, album)) {
            view.setPadding(dp(14), dp(4), dp(14), dp(14))
            view.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                val run = runs.getOrNull(position) ?: return@OnItemClickListener
                startActivity(Intent(ctx, RunDetailActivity::class.java).putExtra("id", run.id))
            }
        }
        empty = TextView(ctx).apply {
            text = "아직 기록이 없어요\n지도에서 첫 러닝을 시작해봐!"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFF8A8F98.toInt())
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(62), dp(18), dp(12))
            addView(label(ctx, "내 러닝", 24f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
            listToggle = modeButton(ctx, "리스트로 보기", R.drawable.ic_record_list, false)
            albumToggle = modeButton(ctx, "앨범으로 보기", R.drawable.ic_record_album, true)
            addView(listToggle, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) })
            addView(albumToggle, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(-1, -2))
            addView(FrameLayout(ctx).apply {
                addView(list, FrameLayout.LayoutParams(-1, -1))
                addView(album, FrameLayout.LayoutParams(-1, -1))
                addView(empty, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun modeButton(ctx: Context, title: String, icon: Int, asAlbum: Boolean) = ImageButton(ctx).apply {
        contentDescription = title
        tooltipText = title
        setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener {
            if (albumMode != asAlbum) {
                albumMode = asAlbum
                ctx.getSharedPreferences("records_ui", Context.MODE_PRIVATE).edit().putBoolean("album", asAlbum).apply()
                display(animate = true)
            }
        }
    }

    override fun onResume() { super.onResume(); load() }
    override fun onHiddenChanged(hidden: Boolean) { if (!hidden && view != null) load() }

    suspend fun demoRowTouch() { list.getChildAt(0)?.demoTouch() }

    private fun load() {
        val ctx = context ?: return
        if (!::empty.isInitialized) return
        val history = RunStore.list(ctx)
        runs = if (RunState.demo) history.filter { it.id == RunState.startMs || it.id < 0 } else history
        display(animate = true)
    }

    private fun display(animate: Boolean) {
        val ctx = requireContext()
        val showAlbum = albumMode && !RunState.demo
        val current = if (showAlbum) album else list
        list.visibility = if (!showAlbum && runs.isNotEmpty()) View.VISIBLE else View.GONE
        album.visibility = if (showAlbum && runs.isNotEmpty()) View.VISIBLE else View.GONE
        empty.visibility = if (runs.isEmpty()) View.VISIBLE else View.GONE
        for ((button, selected) in listOf(listToggle to !showAlbum, albumToggle to showAlbum)) {
            button.isSelected = selected
            button.setColorFilter(if (selected) 0xFF121710.toInt() else Color.WHITE)
            button.background = GradientDrawable().apply {
                cornerRadius = dp(13).toFloat()
                setColor(if (selected) EudMapView.ACCENT else 0x33FFFFFF)
            }
        }
        current.adapter = object : BaseAdapter() {
            override fun getCount() = runs.size
            override fun getItem(position: Int) = runs[position]
            override fun getItemId(position: Int) = runs[position].id
            override fun getView(position: Int, old: View?, parent: ViewGroup): View =
                card(ctx, getItem(position), showAlbum)
        }
        if (animate && android.animation.ValueAnimator.areAnimatorsEnabled() && runs.isNotEmpty()) {
            val motion = AnimationSet(true).apply {
                addAnimation(AlphaAnimation(0f, 1f))
                addAnimation(TranslateAnimation(Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f,
                    Animation.RELATIVE_TO_SELF, 0.22f, Animation.RELATIVE_TO_SELF, 0f))
                duration = 280
            }
            current.layoutAnimation = LayoutAnimationController(motion, 0.14f)
            current.scheduleLayoutAnimation()
        }
    }

    private fun label(ctx: Context, value: String, size: Float, color: Int) = TextView(ctx).apply {
        text = value; textSize = size; setTextColor(color)
    }

    private fun card(ctx: Context, run: SavedRun, asAlbum: Boolean): View {
        val card = LinearLayout(ctx).apply {
            orientation = if (asAlbum) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            Glass.apply(this, 18f, 0xB0)
        }
        val picture = if (asAlbum) run.photos.firstOrNull()?.let { loadRecordPhoto(it, 420) } else null
        val art = ImageView(ctx).apply {
            setImageBitmap(picture ?: RouteIcon.render(run))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        if (asAlbum) card.addView(art, LinearLayout.LayoutParams(-1, dp(116)))
        else card.addView(art, LinearLayout.LayoutParams(dp(84), dp(84)))
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(if (asAlbum) dp(2) else dp(15), if (asAlbum) dp(7) else 0, 0, 0)
        }
        if (run.title.isNotBlank()) info.addView(label(ctx, run.title, 14f, Color.WHITE).apply {
            typeface = Fonts.bold
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(label(ctx, (if (asAlbum) shortDate else date).format(Date(run.startMs)), 12f, 0xFF9AA0A9.toInt()))
        info.addView(label(ctx, RunState.distStr(run.distanceM), if (asAlbum) 21f else 25f, EudMapView.ACCENT).apply { typeface = Fonts.bold })
        info.addView(label(ctx, RunState.timeStr(run.durationSec), 15f, Color.WHITE))
        card.addView(info, LinearLayout.LayoutParams(if (asAlbum) -1 else 0, -2, if (asAlbum) 0f else 1f))
        return if (asAlbum) card else FrameLayout(ctx).apply { setPadding(0, dp(5), 0, dp(5)); addView(card) }
    }
}
