package com.eudero.gano.data

import android.content.Context
import android.graphics.*
import java.io.File
import kotlin.math.cos
import kotlin.math.max

/** 달린 길을 선으로 그려 PNG 아이콘으로 만든다 (기록 목록용). */
object RouteIcon {
    fun file(c: Context, id: Long) = File(File(c.filesDir, "runs").apply { mkdirs() }, "$id.png")

    fun render(r: SavedRun, size: Int = 256): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF14171C.toInt() }
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.18f, size * 0.18f, bg)
        val pts = r.pts; if (pts.size < 2) return bmp
        val k = cos(Math.toRadians(pts[0].lat))
        val xs = pts.map { it.lon * k }; val ys = pts.map { -it.lat }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val pad = size * 0.16f; val span = max(maxX - minX, maxY - minY).coerceAtLeast(1e-6)
        val sc = (size - 2 * pad) / span
        val ox = (size - (maxX - minX) * sc) / 2f; val oy = (size - (maxY - minY) * sc) / 2f
        fun px(i: Int): Float = (ox + (xs[i] - minX) * sc).toFloat()
        fun py(i: Int): Float = (oy + (ys[i] - minY) * sc).toFloat()
        val path = Path(); for (i in pts.indices) if (i == 0) path.moveTo(px(0), py(0)) else path.lineTo(px(i), py(i))
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x55B9FA03; strokeWidth = size * 0.07f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val line = Paint(glow).apply { color = 0xFFB9FA03.toInt(); strokeWidth = size * 0.032f }
        c.drawPath(path, glow); c.drawPath(path, line)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        dot.color = Color.WHITE; c.drawCircle(px(0), py(0), size * 0.035f, dot)
        dot.color = 0xFFB9FA03.toInt(); c.drawCircle(px(pts.size - 1), py(pts.size - 1), size * 0.04f, dot)
        return bmp
    }

    fun shareResult(ctx: Context, run: SavedRun) {
        val bitmap = Bitmap.createBitmap(960, 960, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF14171C.toInt())
        canvas.drawBitmap(render(run, 570), 195f, 105f, null)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = com.eudero.gano.ui.Fonts.bold; textAlign = Paint.Align.CENTER
            color = Color.WHITE; textSize = 36f
        }
        canvas.drawText(run.title.ifBlank { "오늘의 러닝" }, 480f, 82f, paint)
        paint.color = 0xFFB9FA03.toInt(); paint.textSize = 78f
        canvas.drawText(com.eudero.gano.run.RunState.distStr(run.distanceM), 480f, 760f, paint)
        paint.color = Color.WHITE; paint.textSize = 36f
        val stats = com.eudero.gano.run.RunState.timeStr(run.durationSec) + "  ·  " +
            com.eudero.gano.run.RunState.paceStr(run.durationSec, run.distanceM) + "/km"
        canvas.drawText(stats, 480f, 848f, paint)
        val out = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "run-${run.id}.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("러닝 결과", uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(android.content.Intent.createChooser(send, "러닝 결과 공유"))
    }

    fun save(c: Context, r: SavedRun) { file(c, r.id).outputStream().use { render(r).compress(Bitmap.CompressFormat.PNG, 100, it) } }
}
