package com.eudero.gano.data

import android.content.Context
import com.eudero.gano.run.RunState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedRun(val id: Long, val startMs: Long, val durationSec: Long, val distanceM: Double, val avgHr: Int, val pts: List<RunState.Pt>, val title: String = "", val photos: List<String> = emptyList(), val ascentM: Int = 0, val synced: Boolean = false,
    val healthEligible: Boolean = false, val phoneSteps: Long? = null, val endMs: Long = startMs + durationSec * 1000)

/** 로컬 저장(JSON 파일). 나중에 서버 동기화는 이 인터페이스만 바꾸면 된다. */
object RunStore {
    private fun dir(c: Context) = File(c.filesDir, "runs").also { it.mkdirs() }

    @Synchronized fun seedSamples(c: Context) {
        val prefs = c.getSharedPreferences("run_samples", Context.MODE_PRIVATE)
        if (prefs.getBoolean("installed", false)) return
        val codes = listOf("HD-DONGBAEK", "HD-SONGJEONG", "HD-SUYEONG", "HD-MOONTAN", "HD-DALMAJI")
        val now = System.currentTimeMillis()
        codes.forEachIndexed { i, code ->
            val id = -(i + 1L)
            if (File(dir(c), "$id.json").exists()) return@forEachIndexed
            val path = Profiles.get(c, code)
            val start = now - (i + 1) * 86_400_000L
            val msPerM = 345L + i * 14
            val pts = path.map { RunState.Pt(it.lat, it.lon, start + it.d * msPerM) }
            val distance = path.last().d
            save(c, SavedRun(id, start, distance * msPerM / 1000, distance.toDouble(), 0, pts,
                CourseInfo.of(code, code).name + " · 샘플", synced = true))
        }
        prefs.edit().putBoolean("installed", true).apply()
    }

    @Synchronized fun save(c: Context, r: SavedRun) {
        val o = JSONObject().put("id", r.id).put("startMs", r.startMs).put("durationSec", r.durationSec)
            .put("distanceM", r.distanceM).put("avgHr", r.avgHr).put("title", r.title).put("photos", JSONArray(r.photos)).put("ascentM", r.ascentM).put("synced", r.synced)
            .put("healthEligible", r.healthEligible).put("phoneSteps", r.phoneSteps).put("endMs", r.endMs)
        val a = JSONArray()
        r.pts.forEach { a.put(JSONArray().put(it.lat).put(it.lon).put(it.t)) }
        o.put("pts", a)
        File(dir(c), "${r.id}.json").writeText(o.toString())
    }

    @Synchronized fun markSynced(c: Context, id: Long) { get(c, id)?.let { save(c, it.copy(synced = true)) } }

    fun get(c: Context, id: Long): SavedRun? = list(c).firstOrNull { it.id == id }
    @Synchronized fun addPhoto(c: Context, id: Long, path: String) { get(c, id)?.let { save(c, it.copy(photos = it.photos + path)) } }

    @Synchronized fun list(c: Context): List<SavedRun> = dir(c).listFiles()?.filter { it.extension == "json" }?.mapNotNull { f ->
        runCatching {
            val o = JSONObject(f.readText()); val a = o.getJSONArray("pts")
            SavedRun(o.getLong("id"), o.getLong("startMs"), o.getLong("durationSec"), o.getDouble("distanceM"), o.optInt("avgHr"),
                (0 until a.length()).map { i -> a.getJSONArray(i).let { p -> RunState.Pt(p.getDouble(0), p.getDouble(1), p.getLong(2)) } },
                o.optString("title"), o.optJSONArray("photos")?.let { pa -> (0 until pa.length()).map { pa.getString(it) } } ?: emptyList(), o.optInt("ascentM"), o.optBoolean("synced"), o.optBoolean("healthEligible"), if (o.isNull("phoneSteps")) null else o.getLong("phoneSteps"),
                o.optLong("endMs", o.getLong("startMs") + o.getLong("durationSec") * 1000))
        }.getOrNull()
    }?.sortedByDescending { it.startMs } ?: emptyList()
}
