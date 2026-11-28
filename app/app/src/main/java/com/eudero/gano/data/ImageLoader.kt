package com.eudero.gano.data

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.eudero.gano.BuildConfig
import java.io.File
import java.net.URL
import kotlin.concurrent.thread

object ImageLoader {
    /** Small, ready-to-display image for a course card. Called off the UI thread at startup. */
    fun thumbnail(c: Context, path: String): android.graphics.Bitmap? = runCatching {
        fun stream(): java.io.InputStream {
            if (path.startsWith("samples/") || path.startsWith("courses/")) return c.assets.open(path)
            if (path.startsWith("/")) return File(path).inputStream()
            val dir = File(c.filesDir, "cache/img").apply { mkdirs() }
            val file = File(dir, path.replace('/', '_').replace(':', '_'))
            if (!file.isFile) {
                val tmp = File.createTempFile("card-", ".tmp", dir)
                try {
                    val conn = URL(if (path.startsWith("http")) path else BuildConfig.DATA_BASE + path).openConnection()
                    conn.connectTimeout = 4000; conn.readTimeout = 5000
                    conn.getInputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    check(tmp.renameTo(file) || file.isFile)
                } finally { tmp.delete() }
            }
            return file.inputStream()
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        stream().use { BitmapFactory.decodeStream(it, null, options) }
        options.inJustDecodeBounds = false; options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 720) options.inSampleSize *= 2
        stream().use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    fun load(c: Context, path: String, iv: ImageView) {
        if (path.isEmpty()) return
        thread {
            try {
                if (path.startsWith("samples/")) {
                    val bmp = c.assets.open(path).use { BitmapFactory.decodeStream(it) }
                    Handler(Looper.getMainLooper()).post { iv.setImageBitmap(bmp) }; return@thread
                }
                val f = if (path.startsWith("/")) File(path) else File(File(c.filesDir, "cache/img").apply { mkdirs() }, path.replace('/', '_').replace(':', '_')).also {
                    if (!it.exists()) URL(if (path.startsWith("http")) path else BuildConfig.DATA_BASE + path).openStream().use { i -> it.outputStream().use { o -> i.copyTo(o) } }
                }
                val opt = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bmp = BitmapFactory.decodeFile(f.path, opt)
                Handler(Looper.getMainLooper()).post { iv.setImageBitmap(bmp) }
            } catch (_: Exception) { }
        }
    }
}
