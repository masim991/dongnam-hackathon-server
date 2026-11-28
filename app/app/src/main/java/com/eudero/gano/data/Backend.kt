package com.eudero.gano.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.eudero.gano.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 버블(글·사진·위치) 저장소 연결부. 나중에 DB를 바꾸면 이 인터페이스 구현만 바꾸면 된다. */
interface BubbleBackend {
    fun list(area: String): List<Post>?
    fun upload(p: Post, deviceId: String): Boolean
    /** 달리기 기록 + 경로 이미지(PNG) 저장. 나중에 DB를 바꾸면 이 구현만 바꾸면 된다. */
    fun uploadRun(r: SavedRun, png: File?, deviceId: String): Boolean
}

object Backend {
    /** 지금은 폰 안에서만 처리하는 구현(LocalBackend). 나중에 서버 저장으로 바꾸려면 MacMiniBackend 로 바꿔 끼우면 된다. */
    var current: BubbleBackend = LocalBackend
}

/** 서버로 보내지 않는다. 글·사진·달리기 기록은 이미 앱 안에 남아 있으므로 "올림 완료"로만 처리한다. */
object LocalBackend : BubbleBackend {
    override fun list(area: String): List<Post>? = null
    override fun upload(p: Post, deviceId: String) = true
    override fun uploadRun(r: SavedRun, png: File?, deviceId: String) = true
}

/** 유민 맥미니(서버)의 버블 API. */
object MacMiniBackend : BubbleBackend {
    private const val BASE = "https://yumin-server.duckdns.org/bubble"

    override fun list(area: String): List<Post>? = try {
        val c = URL("$BASE/api/posts?area=$area").openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 15000
        val arr = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray("posts")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val img = o.optString("img").let { u -> if (u.startsWith("/")) "https://yumin-server.duckdns.org$u" else u }
            Post(o.getString("id"), o.getDouble("lat"), o.getDouble("lon"), o.getString("type"), o.optString("road"), o.optString("author"),
                o.optString("text"), img, o.optLong("created"), false, true, "", o.optString("visibility", "public"))
        }
    } catch (e: Exception) { null }

    override fun upload(p: Post, deviceId: String): Boolean = try {
        val body = JSONObject().put("id", p.id).put("lat", p.lat).put("lon", p.lon).put("type", p.type).put("text", p.text)
            .put("road", p.road).put("author", p.author).put("visibility", p.visibility).put("created", p.created).put("device", deviceId)
        if (p.img.startsWith("/")) photoBase64(File(p.img))?.let { body.put("photo", it) }
        val c = URL("$BASE/api/posts").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("X-Client-Key", BuildConfig.BUBBLE_KEY)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        c.responseCode in 200..299
    } catch (e: Exception) { false }

    override fun uploadRun(r: SavedRun, png: File?, deviceId: String): Boolean = try {
        val pts = org.json.JSONArray(); r.pts.forEachIndexed { i, p -> if (i % 3 == 0 || i == r.pts.size - 1) pts.put(org.json.JSONArray().put(p.lat).put(p.lon)) }
        val body = JSONObject().put("id", r.id).put("startMs", r.startMs).put("durationSec", r.durationSec).put("distanceM", r.distanceM)
            .put("avgHr", r.avgHr).put("ascentM", r.ascentM).put("title", r.title).put("device", deviceId).put("pts", pts)
        if (png != null && png.exists()) body.put("png", Base64.encodeToString(png.readBytes(), Base64.NO_WRAP))
        val c = URL("$BASE/api/runs").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 25000
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("X-Client-Key", BuildConfig.BUBBLE_KEY)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        c.responseCode in 200..299
    } catch (e: Exception) { false }

    private fun photoBase64(f: File): String? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
        var s = 1; while (o.outWidth / s > 1400) s *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) { null }
}
