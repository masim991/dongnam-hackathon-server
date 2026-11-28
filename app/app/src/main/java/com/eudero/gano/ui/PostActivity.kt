package com.eudero.gano.ui

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.eudero.gano.data.Post
import com.eudero.gano.data.Repo
import java.io.File

/** 글쓰기 / 사진 / 경고·문의. 저장은 로컬에 먼저(지도에 바로 표시), 서버 DB 동기화는 나중. */
class PostActivity : AppCompatActivity() {
    private lateinit var editor: EditText
    private val demoDraft get() = intent.getBooleanExtra("demoDraft", false) && com.eudero.gano.run.RunState.demo
    private var warnMode = false
    private var photo: File? = null
    private var pendingCamera: File? = null
    private lateinit var submit: Button
    private lateinit var preview: ImageView
    private lateinit var photoHint: TextView

    private val cam = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val captured = pendingCamera
        pendingCamera = null
        if (ok && captured != null) selectPhoto(captured) else captured?.delete()
    }
    private val album = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            val f = File(File(filesDir, "photos").apply { mkdirs() }, "p${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            selectPhoto(f)
        }.onFailure { Toast.makeText(this, "사진을 열지 못했어요", Toast.LENGTH_SHORT).show() }
    }
    private fun selectPhoto(file: File) {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Toast.makeText(this, "사진을 열지 못했어요. 다시 골라주세요", Toast.LENGTH_SHORT).show()
            file.delete()
            return
        }
        val options = android.graphics.BitmapFactory.Options()
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > 1600)
            options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        val image = android.graphics.BitmapFactory.decodeFile(file.path, options)
        if (image == null) {
            Toast.makeText(this, "사진을 열지 못했어요. 다시 골라주세요", Toast.LENGTH_SHORT).show()
            file.delete()
            return
        }
        photo = file
        preview.setImageBitmap(image)
        preview.visibility = android.view.View.VISIBLE
        photoHint.visibility = android.view.View.GONE
        submit.isEnabled = true
        submit.alpha = 1f
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean("warnMode", warnMode)
        out.putString("draft", editor.text.toString())
        out.putString("photo", photo?.path)
        out.putString("pendingCamera", pendingCamera?.path)
    }

    private fun takePhoto() {
        val f = File(File(filesDir, "photos").apply { mkdirs() }, "p${System.currentTimeMillis()}.jpg")
        pendingCamera = f
        cam.launch(FileProvider.getUriForFile(this, "$packageName.fileprovider", f))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val lat = intent.getDoubleExtra("lat", 0.0); val lon = intent.getDoubleExtra("lon", 0.0)
        val fromWarningWheel = intent.getStringExtra("mode") == "warn"
        warnMode = s?.getBoolean("warnMode") ?: fromWarningWheel
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(18)) }
        box.addView(TextView(this).apply { text = "새 버블 남기기"; textSize = 24f; setTextColor(0xFFFFFFFF.toInt()) })
        val modes = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val write = RadioButton(this).apply { id = android.view.View.generateViewId(); text = "글쓰기" }
        val report = RadioButton(this).apply { id = android.view.View.generateViewId(); text = "시설 신고" }
        for (button in listOf(write, report)) modes.addView(button, RadioGroup.LayoutParams(0, dp(48), 1f))
        box.addView(modes)
        box.addView(TextView(this).apply {
            text = "운동과 관련 없는 글은 알림 없이 삭제될 수 있어요."; textSize = 13f; setTextColor(0xFFB9FA03.toInt()); setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Glass.bg(resources.displayMetrics.density, 12f, 0xB0)
        }, android.widget.LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        box.addView(TextView(this).apply { text = "📍 %.5f, %.5f".format(lat, lon); setTextColor(0xFF8A8F98.toInt()); setPadding(0, 16, 0, 16) })
        val kind = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        if (fromWarningWheel) {
            kind.addView(RadioButton(this).apply { text = "위험 경고"; id = 1; isChecked = true })
            kind.addView(RadioButton(this).apply { text = "시설 신고"; id = 3 })
            kind.addView(RadioButton(this).apply { text = "문의"; id = 2 })
            box.addView(kind)
        }
        val et = EditText(this).apply { hint = if (warnMode) "무슨 일이야? (가로등 고장, 벤치 파손, 길 붕괴, 공사...)" else "여기에 글을 남겨보세요!"; setHintTextColor(0xFF8A8F98.toInt()); setTextColor(0xFFFFFFFF.toInt()); minLines = 4; gravity = android.view.Gravity.TOP }
        editor = et
        et.setText(s?.getString("draft").orEmpty())
        box.addView(et)
        // 사진 넣기 유도: 점선 네모 + 카메라 이모지
        val dens = resources.displayMetrics.density
        val dash = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16 * dens; setColor(0x14FFFFFF); setStroke((2 * dens).toInt(), 0xAAB9FA03.toInt(), 10 * dens, 7 * dens) }
            setOnClickListener {
                val dlg = android.app.AlertDialog.Builder(this@PostActivity).setItems(arrayOf("사진 찍기", "앨범에서 고르기")) { _, w -> if (w == 0) takePhoto() else album.launch("image/*") }.create()
                dlg.show(); Glass.dialog(dlg)
            }
        }
        photoHint = TextView(this).apply { text = "📷\n사진을 추가해야 올릴 수 있어요"; textSize = 15f; setTextColor(0xFFB9FA03.toInt()); gravity = android.view.Gravity.CENTER; setLineSpacing(0f, 1.25f) }
        dash.addView(photoHint, FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER))
        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = android.view.View.GONE; clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16 * dens } }
        dash.addView(preview, FrameLayout.LayoutParams(-1, -1))
        box.addView(dash, LinearLayout.LayoutParams(-1, dp(150)).also { it.topMargin = dp(12); it.bottomMargin = dp(12) })
        submit = Button(this).apply {
            isEnabled = false; alpha = 0.45f
            text = if (warnMode) "신고하기" else "올리기"; setTextColor(0xFF0D0F13.toInt()); setBackgroundColor(0xFFB9FA03.toInt())
            setOnClickListener {
                val selected = photo?.takeIf { it.isFile && it.length() > 0 } ?: run {
                    Toast.makeText(context, "사진을 추가해야 올릴 수 있어요", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (demoDraft) {
                    setResult(RESULT_OK, android.content.Intent().putExtra("text", et.text.toString()))
                    finish()
                    return@setOnClickListener
                }
                if (!PostLimiter.allow(context)) { Toast.makeText(context, "잠시 후에 다시 올릴 수 있어요 (1분에 2개까지)", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                PostLimiter.record(context)
                val type = if (warnMode && !fromWarningWheel) "facility" else if (warnMode) when (kind.checkedRadioButtonId) { 3 -> "facility"; 2 -> "ask"; else -> "warn" } else "person"
                val now = System.currentTimeMillis()
                Repo.addLocal(context, Post("L$now", lat, lon, type, Repo.nearestRoad(lat, lon), "나", et.text.toString().ifBlank { "📷" }, selected.path, now, true, false, "p1", "public"))
                Toast.makeText(context, "올렸어요!", Toast.LENGTH_LONG).show()
                finish()
            }
        }
        box.addView(submit)
        box.addView(TextView(this).apply {
            text = "GPS 위치에는 오차가 있을 수 있어요."; textSize = 12f; setTextColor(0xFFA1A5AE.toInt())
            gravity = android.view.Gravity.CENTER; setPadding(0, dp(8), 0, 0)
        })
        modes.setOnCheckedChangeListener { _, checked ->
            warnMode = checked == report.id
            kind.visibility = if (fromWarningWheel && warnMode) android.view.View.VISIBLE else android.view.View.GONE
            et.hint = if (warnMode) "어떤 시설이 고장 났나요?" else "여기에 글을 남겨보세요!"
            submit.text = if (warnMode) "신고하기" else "올리기"
        }
        modes.check(if (warnMode) report.id else write.id)
        pendingCamera = s?.getString("pendingCamera")?.let(::File)
        s?.getString("photo")?.let { selectPhoto(File(it)) }
        setContentView(Glass.detailScreen(this, ScrollView(this).apply { addView(box) }))
    }

    suspend fun writeDemoPost() {
        if (!demoDraft) return
        val ime = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        editor.demoTouch(dp(25).toFloat(), dp(20).toFloat())
        editor.requestFocus()
        ime.showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        kotlinx.coroutines.delay(550)
        val initials = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        for (ch in "오늘은 파도 소리 들으면서 완주! 다음에도 이 길로 와야지.") {
            val syllable = ch.code - 0xAC00
            if (syllable in 0 until 11172) {
                val start = editor.length()
                editor.append(initials[syllable / 588].toString())
                kotlinx.coroutines.delay(48)
                val open = (ch.code - syllable % 28).toChar()
                editor.text.replace(start, start + 1, open.toString())
                if (open != ch) {
                    kotlinx.coroutines.delay(42)
                    editor.text.replace(start, start + 1, ch.toString())
                }
                editor.setSelection(editor.length())
            } else editor.append(ch.toString())
            kotlinx.coroutines.delay(if (ch == '!') 250 else 75)
        }
        kotlinx.coroutines.delay(700)
        ime.hideSoftInputFromWindow(editor.windowToken, 0)
        kotlinx.coroutines.delay(350)
        val sample = File(cacheDir, "demo-beach.jpg")
        assets.open("samples/photo_11.jpg").use { input -> sample.outputStream().use { input.copyTo(it) } }
        selectPhoto(sample)
        kotlinx.coroutines.delay(1300)
        submit.demoTouch()
        submit.performClick()
    }

}

/** 도배 방지: 1분에 최대 2개까지만 올릴 수 있다(시연 모드에서는 풀어둠). */
object PostLimiter {
    private const val KEY = "post_times"
    private fun prefs(c: android.content.Context) = c.getSharedPreferences("post_limit", android.content.Context.MODE_PRIVATE)
    private fun recent(c: android.content.Context): List<Long> {
        val now = System.currentTimeMillis()
        return (prefs(c).getString(KEY, "") ?: "").split(",").mapNotNull { it.toLongOrNull() }.filter { now - it < 60_000 }
    }
    fun allow(c: android.content.Context) = com.eudero.gano.run.RunState.demo || recent(c).size < 2
    fun record(c: android.content.Context) { if (!com.eudero.gano.run.RunState.demo) prefs(c).edit().putString(KEY, (recent(c) + System.currentTimeMillis()).joinToString(",")).apply() }
}
