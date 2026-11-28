package com.eudero.gano.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 길 주변 500m: 신호등/횡단보도 "있다/없다". OSM Overpass 실시간 요청 먼저, 실패하면 저장 DB로 대체. */
object Nearby {
    data class Result(val signals: Int, val crossings: Int, val live: Boolean)

    fun query(lat: Double, lon: Double): Result {
        try {
            val q = "[out:json][timeout:8];(node(around:500,$lat,$lon)[highway=traffic_signals];node(around:500,$lat,$lon)[highway=crossing];node(around:500,$lat,$lon)[crossing];);out tags;"
            val c = URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5000; c.readTimeout = 9000
            c.setRequestProperty("User-Agent", "ddwigobo-hackathon/1.1")
            c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
            val els = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray("elements")
            var s = 0; var cr = 0
            for (i in 0 until els.length()) {
                val t = els.getJSONObject(i).optJSONObject("tags")
                if (t?.optString("highway") == "traffic_signals" || t?.optString("crossing") == "traffic_signals") s++ else cr++
            }
            return Result(s, cr, true)
        } catch (_: Exception) { }
        val (s, c) = Repo.countNear(lat, lon)
        return Result(s, c, false)
    }
}
