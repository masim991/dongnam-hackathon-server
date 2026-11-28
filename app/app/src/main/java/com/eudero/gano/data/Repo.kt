package com.eudero.gano.data

import android.content.Context
import android.util.JsonReader
import com.eudero.gano.BuildConfig
import com.eudero.maplib.Road
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 데이터 흐름: 로컬 캐시 먼저 보여줌 -> 새로고침 때 서버(맥미니 /files/data)에서 받아 갱신.
 * 서버에서 받은 데이터는 3시간 뒤 캐시에서 삭제. 내가 쓴 글은 로컬에 남고(동기화 대기), DB 업로드는 나중에.
 */
object Repo {
    const val TTL = 3 * 3600 * 1000L
    @Volatile var roads: List<Road> = emptyList()
    @Volatile var nodes: List<Node> = emptyList()
    @Volatile var serverPosts: List<Post> = emptyList()
    @Volatile var fetchedAt = 0L

    private fun cacheDir(c: Context) = File(c.filesDir, "cache").apply { mkdirs() }
    private fun prefs(c: Context) = c.getSharedPreferences("repo", Context.MODE_PRIVATE)

    /** 3시간 지난 서버 데이터(도로/글/이미지 캐시) 삭제. */
    fun purge(c: Context) {
        val t = prefs(c).getLong("fetchedAt", 0)
        if (t != 0L && System.currentTimeMillis() - t > TTL) {
            cacheDir(c).deleteRecursively(); prefs(c).edit().remove("fetchedAt").apply()
            roads = emptyList(); nodes = emptyList(); serverPosts = emptyList(); fetchedAt = 0
        }
    }

    fun loadCache(c: Context): Boolean {
        purge(c)
        val rf = File(cacheDir(c), "roads.json.gz"); val pf = File(cacheDir(c), "posts.json")
        if (!rf.exists()) return false
        return try {
            parseRoads(rf); if (pf.exists()) parsePosts(pf.readText())
            fetchedAt = prefs(c).getLong("fetchedAt", 0); true
        } catch (e: Exception) { false }
    }

    /** 네트워크로 새로 받기 (백그라운드 스레드에서 호출). */
    fun refresh(c: Context): Boolean = try {
        val dir = cacheDir(c)
        download(BuildConfig.DATA_BASE + "roads_${Areas.current.code}.json.gz", File(dir, "roads.json.gz"))
        runCatching { download(BuildConfig.DATA_BASE + "posts_${Areas.current.code}.json", File(dir, "posts.json")) }
        File(dir, "img").deleteRecursively()
        prefs(c).edit().putLong("fetchedAt", System.currentTimeMillis()).apply()
        loadCache(c)
    } catch (e: Exception) { false }

    /** 캐시가 있으면 쓰고, 없거나 force면 새로 받는다. */
    fun refreshOrLoad(c: Context, force: Boolean): Boolean {
        val ok = if (!force && loadCache(c)) true else refresh(c)
        syncPending(c); runCatching { syncRuns(c) }
        return ok || roads.isNotEmpty()
    }

    /** 읽기는 실제 DB 응답까지 기다린다. 쓰기 정책은 Backend.current가 담당한다. */
    fun loadMapBubbles(c: Context): List<Post>? {
        val remote = MacMiniBackend.list(Areas.current.code) ?: return null
        val mine = readLocalPosts(c)
        serverPosts = remote
        return combinedPosts(c, mine)
    }

    private fun deviceId(c: Context): String = prefs(c).getString("device", null) ?: java.util.UUID.randomUUID().toString().take(12).also { prefs(c).edit().putString("device", it).apply() }

    /** 아직 맥미니에 못 올린 달리기 기록과 경로 이미지를 올린다. */
    fun syncRuns(c: Context) {
        for (r in RunStore.list(c)) if (!r.synced && Backend.current.uploadRun(r, RouteIcon.file(c, r.id), deviceId(c))) RunStore.markSynced(c, r.id)
    }

    /** 아직 서버에 못 올린 내 글을 올린다. */
    fun syncPending(c: Context) {
        val mine = localPosts(c)
        if (mine.none { !it.synced }) return
        val done = HashSet<String>()
        for (p in mine) if (!p.synced && Backend.current.upload(p, deviceId(c))) done.add(p.id)
        if (done.isNotEmpty()) writeLocal(c, mine.map { if (it.id in done) it.copy(synced = true) else it })
    }

    private fun download(url: String, to: File) {
        val tmp = File(to.path + ".tmp")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 20000
        conn.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        tmp.renameTo(to)
    }

    private fun parseRoads(f: File) {
        val rs = ArrayList<Road>(); val ns = ArrayList<Node>()
        JsonReader(GZIPInputStream(f.inputStream()).bufferedReader()).use { r ->
            r.beginObject()
            while (r.hasNext()) when (r.nextName()) {
                "ways" -> { r.beginArray(); while (r.hasNext()) readWay(r)?.let { rs.add(it) }; r.endArray() }
                "nodes" -> { r.beginArray(); while (r.hasNext()) { r.beginArray(); val la = r.nextDouble(); val lo = r.nextDouble(); val k = r.nextString(); r.endArray(); ns.add(Node(la, lo, k == "s")) }; r.endArray() }
                else -> r.skipValue()
            }
            r.endObject()
        }
        roads = rs; nodes = ns
    }

    private fun readWay(r: JsonReader): Road? {
        var id = 0L; var n = ""; var n0 = ""; var hw = ""; var s = -1f
        val la = ArrayList<Double>(); val lo = ArrayList<Double>()
        r.beginObject()
        while (r.hasNext()) when (r.nextName()) {
            "id" -> id = r.nextLong(); "n" -> n = r.nextString(); "n0" -> n0 = r.nextString(); "hw" -> hw = r.nextString()
            "s" -> if (r.peek() == android.util.JsonToken.NULL) r.nextNull() else s = r.nextDouble().toFloat()
            "p" -> { r.beginArray(); while (r.hasNext()) { r.beginArray(); la.add(r.nextDouble()); lo.add(r.nextDouble()); r.endArray() }; r.endArray() }
            else -> r.skipValue()
        }
        r.endObject()
        return if (la.size >= 2) Road(id, n, n0, hw, s, la.toDoubleArray(), lo.toDoubleArray()) else null
    }

    private fun parsePosts(txt: String) {
        val a = JSONObject(txt).getJSONArray("posts")
        serverPosts = (0 until a.length()).map { postOf(a.getJSONObject(it), false) }
    }

    private fun postOf(o: JSONObject, local: Boolean) = Post(o.getString("id"), o.getDouble("lat"), o.getDouble("lon"), o.getString("type"),
        o.optString("road"), o.optString("author"), o.optString("text"), o.optString("img"), o.optLong("created"), local, o.optBoolean("synced", !local), o.optString("authorId", if (local) "p1" else ""), o.optString("visibility", "public"))

    // ---- 내가 쓴 글 (로컬 먼저) ----
    private fun localFile(c: Context) = File(c.filesDir, "localposts.json")
    fun localPosts(c: Context): List<Post> = runCatching { readLocalPosts(c) }.getOrDefault(emptyList())
    @Synchronized private fun readLocalPosts(c: Context): List<Post> {
        val file = localFile(c)
        if (!file.exists()) return emptyList()
        val a = JSONArray(file.readText())
        return (0 until a.length()).map { postOf(a.getJSONObject(it), true) }
    }

    @Synchronized private fun writeLocal(c: Context, list: List<Post>) {
        val a = JSONArray(); list.forEach {
            a.put(JSONObject().put("id", it.id).put("lat", it.lat).put("lon", it.lon).put("type", it.type).put("road", it.road)
                .put("author", it.author).put("text", it.text).put("img", it.img).put("created", it.created).put("synced", it.synced).put("authorId", it.authorId).put("visibility", it.visibility))
        }
        localFile(c).writeText(a.toString())
    }

    /** 내 글은 먼저 내 화면에 보이게 저장하고, 곧바로 서버 업로드를 시도한다. */
    @Synchronized fun addLocal(c: Context, p: Post) {
        require(p.img.isNotBlank()) { "사진을 추가해야 올릴 수 있어요" }
        writeLocal(c, localPosts(c) + p.copy(synced = false))
        kotlin.concurrent.thread { runCatching { syncPending(c) } }
    }

    /** 서버 글 + 내 글(중복 제거). */
    /** 샘플 + 서버 글(옛 시드 제외) + 내 글. */
    fun allPosts(c: Context): List<Post> = combinedPosts(c, localPosts(c))
    private val oldSeedId = Regex("s[0-9]+_[0-9]+")
    private fun combinedPosts(c: Context, mine: List<Post>): List<Post> {
        val ids = mine.map { it.id }.toSet()
        return SampleData.posts(c) + serverPosts.filter { it.id !in ids && !oldSeedId.matches(it.id) } + mine
    }

    /** 가장 가까운 길 이름 (글에 붙이기용). */
    fun nearestRoad(lat: Double, lon: Double): String {
        var best = ""; var bd = 1e9
        for (r in roads) {
            if (r.name.isEmpty()) continue
            for (i in r.lat.indices step 2) { val d = hypot(r.lat[i] - lat, (r.lon[i] - lon) * cos(Math.toRadians(lat))); if (d < bd) { bd = d; best = r.name } }
        }
        return if (bd < 0.0008) best else ""
    }

    /** 반경 500m 안 신호등/횡단보도 개수. */
    fun countNear(lat: Double, lon: Double, m: Double = 500.0): Pair<Int, Int> {
        var s = 0; var cr = 0
        for (n in nodes) {
            val dy = (n.lat - lat) * 111000; val dx = (n.lon - lon) * 111000 * cos(Math.toRadians(lat))
            if (hypot(dx, dy) <= m) if (n.signal) s++ else cr++
        }
        return s to cr
    }
}
