package com.eudero.gano.ui

import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.eudero.gano.data.RouteIcon
import com.eudero.gano.data.RunStore
import com.eudero.gano.data.SavedRun
import com.eudero.gano.run.RunState
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** 러닝 기록 상세: 달린 길, 기록, 찍은 사진, 오운완 글자 넣기, 공유. */
class RunDetailActivity : AppCompatActivity() {
    var trackReady = false
        private set
    private var trackMap: com.eudero.maplib.KakaoBackground? = null
    private lateinit var run: SavedRun
    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private lateinit var photoRow: LinearLayout
    private lateinit var preview: ImageView
    private lateinit var caption: EditText
    private var selected: String? = null
    private var pending: File? = null
    private val album = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            val f = File(File(filesDir, "runphotos").apply { mkdirs() }, "a${run.id}_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            RunStore.addPhoto(this, run.id, f.path); run = RunStore.get(this, run.id)!!; selected = f.path; buildPhotos(); render()
        }.onFailure { Toast.makeText(this, "사진을 열지 못했어요", Toast.LENGTH_SHORT).show() }
    }
    /** 사진이 없을 때 쓰는 코스 사진 (assets/courses/<코드>.jpg, 기록 제목이 코스 이름과 맞을 때) */
    private fun courseBitmap(): Bitmap? = runCatching {
        val code = com.eudero.gano.data.CourseRepo.all.firstOrNull { run.title.startsWith(it.name) }?.code ?: return null
        assets.open("courses/$code.jpg").use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private val cam = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pending ?: return@registerForActivityResult
        pending = null
        if (ok && file.length() > 0L) {
            RunStore.addPhoto(this, run.id, file.path)
            run = RunStore.get(this, run.id) ?: return@registerForActivityResult
            selected = file.path
            buildPhotos()
            render()
        } else file.delete()
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        run = RunStore.get(this, intent.getLongExtra("id", 0)) ?: run { finish(); return }
        selected = s?.getString("selectedPhoto")?.takeIf { it in run.photos } ?: run.photos.firstOrNull()
        pending = s?.getString("pendingCamera")?.let(::File)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(24)) }
        val fmt = SimpleDateFormat("M월 d일 (E) a h:mm", Locale.KOREA)
        root.addView(TextView(this).apply { text = if (run.title.isNotEmpty()) run.title else "내 러닝"; textSize = 22f; setTextColor(Color.WHITE); typeface = Fonts.bold })
        root.addView(TextView(this).apply { text = fmt.format(Date(run.startMs)); textSize = 13f; setTextColor(0xFF9AA0A9.toInt()); setPadding(0, 0, 0, dp(12)) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); Glass.apply(this, 20f, 0xB8) }
        val frame = FrameLayout(this)
        val status = TextView(this).apply {
            text = if (run.pts.isEmpty()) "저장된 위치가 없어요" else "경로를 불러오고 있어요"
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); setBackgroundColor(0xFF14171C.toInt())
        }
        if (run.pts.isNotEmpty()) {
            val points = run.pts.map { com.eudero.maplib.GeoPoint(it.lat, it.lon) }
            val map = com.eudero.maplib.KakaoBackground(this, com.eudero.maplib.EudMapView.kakaoKey)
            trackMap = map
            map.onState = { state -> map.post {
                if (!isDestroyed) {
                    if (state == "ready") {
                        map.drawRecordedTrack(points)
                        trackReady = true
                        status.visibility = android.view.View.GONE
                    } else status.text = "지도를 불러오지 못했어요"
                }
            } }
            frame.addView(map)
        }
        frame.addView(status)
        box.addView(frame, LinearLayout.LayoutParams(-1, dp(208)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(12), 0, 0) }
        info.addView(TextView(this).apply { text = RunState.distStr(run.distanceM); textSize = 30f; setTextColor(0xFFB9FA03.toInt()); typeface = Fonts.bold })
        info.addView(TextView(this).apply { text = RunState.timeStr(run.durationSec); textSize = 18f; setTextColor(Color.WHITE) })
        info.addView(TextView(this).apply { text = RunState.paceStr(run.durationSec, run.distanceM) + "/km"; textSize = 14f; setTextColor(0xFFB8BEC8.toInt()) })
        if (run.healthEligible) {
            val metrics = TextView(this).apply { textSize = 14f; setTextColor(Color.WHITE) }
            info.addView(metrics)
            lifecycleScope.launch {
                val (steps, hr) = runCatching { com.eudero.gano.health.HeartRate.metrics(this@RunDetailActivity, run) }
                    .getOrDefault(run.phoneSteps to null)
                metrics.text = listOfNotNull(steps?.let { "걸음 수 ${it}걸음" },
                    (hr ?: run.avgHr.toLong().takeIf { it > 0 })?.let { "평균 심박수 ${it}bpm" }).joinToString(" · ")
            }
        }
        box.addView(info); root.addView(box)

        root.addView(TextView(this).apply { text = "찍은 사진"; textSize = 16f; setTextColor(Color.WHITE); typeface = Fonts.bold; setPadding(0, dp(20), 0, dp(8)) })
        photoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(photoRow) })

        root.addView(TextView(this).apply { text = "오운완 만들기"; textSize = 16f; setTextColor(Color.WHITE); typeface = Fonts.bold; setPadding(0, dp(22), 0, dp(8)) })
        preview = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; Glass.apply(this, 16f, 0x60) }
        root.addView(preview, LinearLayout.LayoutParams(-1, -2))
        caption = EditText(this).apply {
            setText((if (run.title.isNotEmpty()) "${run.title}\n" else "") + "${RunState.distStr(run.distanceM)} · ${RunState.timeStr(run.durationSec)}"); setTextColor(Color.WHITE)
            addTextChangedListener(object : android.text.TextWatcher { override fun afterTextChanged(e: android.text.Editable?) { render() }; override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}; override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {} })
        }
        root.addView(caption)
        root.addView(Button(this).apply { text = "공유하기"; setTextColor(0xFF0D0F13.toInt()); background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 14 * d; setColor(0xFFB9FA03.toInt()) }; setOnClickListener { share() } }, LinearLayout.LayoutParams(-1, dp(50)).also { it.topMargin = dp(10) })
        setContentView(Glass.detailScreen(this, ScrollView(this).apply { addView(root) }))
        buildPhotos(); render()
    }

    override fun onResume() { super.onResume(); trackMap?.onResume() }
    override fun onPause() { trackMap?.onPause(); super.onPause() }
    override fun onDestroy() { trackMap?.onDestroy(); super.onDestroy() }

    private fun buildPhotos() {
        photoRow.removeAllViews()
        run.photos.forEach { path ->
            photoRow.addView(ImageView(this).apply {
                setImageBitmap(decode(path, 260)); scaleType = ImageView.ScaleType.FIT_CENTER; clipToOutline = true
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 14 * d; setStroke(dp(if (path == selected) 3 else 0), 0xFFB9FA03.toInt()) }
                setOnClickListener { selected = path; buildPhotos(); render() }
            }, LinearLayout.LayoutParams(dp(88), dp(88)).also { it.rightMargin = dp(8) })
        }
        photoRow.addView(TextView(this).apply {
            text = "＋\n사진 추가"; gravity = Gravity.CENTER; textSize = 12f; setTextColor(0xFFB9FA03.toInt()); Glass.apply(this, 14f, 0x80)
            setOnClickListener { choosePhotoSource() }
        }, LinearLayout.LayoutParams(dp(88), dp(88)).also { it.rightMargin = dp(8) })
    }

    private fun choosePhotoSource() {
        val dialog = android.app.AlertDialog.Builder(this).setTitle("사진 추가")
            .setItems(arrayOf("카메라로 찍기", "앨범에서 고르기")) { _, index ->
                if (index == 1) {
                    album.launch("image/*")
                } else {
                    val file = File(File(filesDir, "runphotos").apply { mkdirs() }, "r${run.id}_${System.currentTimeMillis()}.jpg")
                    pending = file
                    runCatching {
                        cam.launch(FileProvider.getUriForFile(this, "$packageName.fileprovider", file))
                    }.onFailure {
                        pending = null
                        Toast.makeText(this, "카메라를 열지 못했어요", Toast.LENGTH_SHORT).show()
                    }
                }
            }.create()
        dialog.show()
        Glass.dialog(dialog)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("selectedPhoto", selected)
        outState.putString("pendingCamera", pending?.path)
    }

    private fun decode(path: String, max: Int) = loadRecordPhoto(path, max)

    /** 오운완 카드: 사진 + 지역·"오운완"·코스 이름 + 오른쪽 아래 내 경로 카드 */
    private fun compose(): Bitmap {
        val base = selected?.let { decode(it, 1080) } ?: courseBitmap() ?: RouteIcon.render(run, 1080)
        val cw = base.width
        val ch = base.height
        val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(base, 0f, 0f, null)
        val g = Paint().apply { shader = LinearGradient(0f, ch * 0.5f, 0f, ch.toFloat(), 0x00000000, 0xE6000000.toInt(), Shader.TileMode.CLAMP) }
        c.drawRect(0f, ch * 0.5f, cw.toFloat(), ch.toFloat(), g)
        val u = cw / 1080f; val pad = 46f * u
        val lines = caption.text.toString().split("\n").filter { it.isNotBlank() }
        val course = com.eudero.gano.data.CourseRepo.all.firstOrNull { run.title.startsWith(it.name) }
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold }
        var y = ch - pad
        // 캡션(코스 이름 등) 아래부터 위로 쌓기
        tp.color = Color.WHITE; tp.textSize = 40f * u
        for (l in lines.reversed()) { c.drawText(l, pad, y, tp); y -= 52f * u }
        tp.color = Color.WHITE; tp.textSize = 118f * u; y -= 10f * u; c.drawText("오운완", pad, y, tp)
        tp.color = 0xFFB9FA03.toInt(); tp.textSize = 36f * u; y -= 138f * u; c.drawText(course?.region ?: "내 러닝", pad, y, tp)
        // 오른쪽 아래: 내가 달린 길 카드
        val cs = 236f * u; val cx = cw - pad - cs; val cy = ch - pad - cs
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC15181D.toInt() }
        c.drawRoundRect(RectF(cx, cy, cx + cs, cy + cs), 28f * u, 28f * u, card)
        val rt = RouteIcon.render(run, 256); val dst = RectF(cx + 20f * u, cy + 14f * u, cx + cs - 20f * u, cy + cs - 54f * u)
        c.save(); c.clipRect(dst); c.drawBitmap(rt, null, RectF(dst.centerX() - dst.height() / 2, dst.top, dst.centerX() + dst.height() / 2, dst.bottom), Paint(Paint.FILTER_BITMAP_FLAG)); c.restore()
        tp.color = Color.WHITE; tp.textSize = 30f * u; tp.textAlign = Paint.Align.CENTER
        c.drawText("%.1f km".format(run.distanceM / 1000.0), cx + cs / 2, cy + cs - 16f * u, tp)
        return out
    }

    private fun render() { preview.setImageBitmap(compose()) }

    private fun share() {
        val f = File(File(cacheDir, "share").apply { mkdirs() }, "ohwan.png")
        f.outputStream().use { compose().compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, caption.text.toString()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "오운완 공유"))
    }
}
