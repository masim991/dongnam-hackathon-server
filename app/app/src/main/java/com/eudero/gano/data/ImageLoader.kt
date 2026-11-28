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
