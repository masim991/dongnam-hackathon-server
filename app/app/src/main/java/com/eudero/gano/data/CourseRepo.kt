package com.eudero.gano.data

import android.content.Context
import android.util.JsonReader
import com.eudero.maplib.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.hypot

/** 추천 산책길 9개 + 경사/신호등/횡단보도(해운대) + 오늘의 러닝 점수. 전부 불러온 뒤에 한꺼번에 보여준다. */
object CourseRepo {
    private const val API = "https://dongnam-hackathon.magabus.workers.dev"
    @Volatile var courses: List<Course> = emptyList()
    @Volatile private var crossingsByCourse: Map<String, List<Dot>> = emptyMap()
    @Volatile private var pathsByCourse: Map<String, List<PP>> = emptyMap()
    @Volatile var regional: List<Course> = emptyList()
    val all: List<Course> get() = courses + regional
    @Volatile var segs: List<Seg> = emptyList()
    @Volatile var dots: List<Dot> = emptyList()
    @Volatile var walkingRoads: List<Road> = emptyList()
        private set
    @Volatile var approachRoads: List<Road> = emptyList()
        private set
    @Volatile var weatherScore = -1
    @Volatile var weatherLabel = ""

    private fun dir(c: Context) = File(c.filesDir, "cache/team").apply { mkdirs() }

    private fun fetch(url: String, to: File, force: Boolean): String? {
        if (!force && to.isFile && System.currentTimeMillis() - to.lastModified() < Repo.TTL) return to.readText()
        return runCatching {
            val con = URL(url).openConnection() as HttpURLConnection
            try {
                con.connectTimeout = 6000
                con.readTimeout = 10000
                val data = con.inputStream.bufferedReader().use { it.readText() }
                JSONObject(data)
                val temp = File(to.path + ".new")
                temp.writeText(data)
                temp.renameTo(to)
                data
            } finally {
                con.disconnect()
            }
        }.getOrElse { if (to.isFile) to.readText() else null }
    }

    /** @param step 끝난 단계 수 콜백 (최대 total). 백그라운드 스레드에서 호출. */
    fun loadAll(c: Context, force: Boolean, total: Int, step: (Int) -> Unit): Boolean {
        val done = AtomicInteger(0); val ok = AtomicInteger(0)
        fun fin(good: Boolean) { if (good) ok.incrementAndGet(); step(done.incrementAndGet()) }
        val ts = listOf(
            thread {
                val base = runCatching { Repo.refreshOrLoad(c, force) }.getOrDefault(false)
                val walked = runCatching {
                    val data = fetch("$API/data/haeundae/walking-network.json", File(dir(c), "walk_network.json"), force)
                    if (data == null) null else readWalkingRoads(data)
                }.getOrNull()
                if (walked != null && walked.second.isNotEmpty()) {
                    walkingRoads = walked.first
                    approachRoads = walked.second
                }
                fin(base || walkingRoads.isNotEmpty())
            },
            thread {
                fin(runCatching {
                    val cache = dir(c)
                    val refined = fetch("$API/data/haeundae/courses/index.json", File(cache, "refined_index.json"), force)
                    val list = refined ?: fetch("$API/api/courses", File(cache, "courses.json"), force) ?: error("no courses")
                    val arr = JSONObject(list).getJSONArray("courses")
                    val out = arrayOfNulls<Course>(arr.length())
                    val crossings = java.util.concurrent.ConcurrentHashMap<String, List<Dot>>()
                    val paths = java.util.concurrent.ConcurrentHashMap<String, List<PP>>()
                    val th = (0 until arr.length()).map { i -> thread {
                        runCatching {
                            val row = arr.getJSONObject(i)
                            val code = row.getString("code")
                            val path = row.optString("geojson")
                            val updated = if (path.startsWith("/data/haeundae/courses/"))
                                fetch(API + path, File(cache, "refined_$code.json"), force) else null
                            val geo = updated ?: fetch("$API/api/courses/$code/geojson", File(cache, "course_$code.json"), force)
                                ?: return@runCatching
                            val info = CourseInfo.of(code, row.getString("name"))
                            val parts = parseLines(geo)
                            val reference = Profiles.get(c, code).filter { it.lat != 0.0 }
                            val line = TrailGeometry.mainLine(parts, reference, code == "HD-DONGBAEK") ?: return@runCatching
                            val profile = buildProfile(line, reference)
                            val course = Course(code, info.name, info.tagline, info.badge, profile.last().d,
                                listOf(line), row.optString("region", "부산"), true)
                            paths[code] = profile
                            crossings[code] = CourseProfile(profile, readCrossings(geo)).along.map { it.dot }
                            out[i] = course
                        }
                    } }
                    th.forEach { it.join() }
                    crossingsByCourse = crossings.toMap()
                    pathsByCourse = paths.toMap()
                    courses = out.filterNotNull(); courses.isNotEmpty()
                }.getOrDefault(false))
            },
            thread {
                fin(runCatching {
                    val t = fetch("$API/data/haeundae/trail-context.json", File(dir(c), "context.json"), force) ?: error("no ctx")
                    parseContext(t); true
                }.getOrDefault(false))
            },
            thread {
                fin(runCatching {
                    val t = URL("$API/api/weather").openConnection().apply { connectTimeout = 6000; readTimeout = 10000 }.getInputStream().bufferedReader().use { it.readText() }
                    val s = JSONObject(t).getJSONObject("summary").getJSONObject("해운대")
                    weatherScore = s.optInt("score", -1); weatherLabel = s.optString("label"); true
                }.getOrDefault(false))
            }
        )
        ts.forEach { it.join() }
        regional = runCatching { loadRegional(c) }.getOrDefault(emptyList())
        Course.assignLineColors(all)
        return courses.isNotEmpty()
    }

    /** 부울경 해운대 외 대표 길 (기기에 넣어둔 데이터). 길 표시·소개만 사용. */
    private fun loadRegional(c: Context): List<Course> {
        val a = org.json.JSONArray(c.assets.open("regional_routes.json").bufferedReader().use { it.readText() })
        return (0 until a.length()).map {
            val o = a.getJSONObject(it); val p = o.getJSONArray("pts")
            val la = DoubleArray(p.length()) { k -> p.getJSONArray(k).getDouble(0) }; val lo = DoubleArray(p.length()) { k -> p.getJSONArray(k).getDouble(1) }
            Course(o.getString("code"), o.getString("name"), o.getString("tagline"), o.getString("badge"), o.getInt("lengthM"), listOf(Line(la, lo)), o.getString("region"), false)
        }
    }

    private fun parseLines(json: String): List<Line> {
        val feats = JSONObject(json).getJSONArray("features"); val out = ArrayList<Line>()
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i); if (f.optJSONObject("properties")?.optString("layer", "course") != "course") continue
            val props = f.optJSONObject("properties")
            if (props?.optString("highway") == "gap_bridge") continue
            val g = f.optJSONObject("geometry") ?: continue; val gt = g.optString("type"); if (gt != "LineString" && gt != "MultiLineString") continue; val co = g.getJSONArray("coordinates")
            val parts = if (gt == "MultiLineString") (0 until co.length()).map { co.getJSONArray(it) } else listOf(co)
            for (p in parts) if (p.length() >= 2) {
                val la = DoubleArray(p.length()); val lo = DoubleArray(p.length())
                for (k in 0 until p.length()) { val q = p.getJSONArray(k); lo[k] = q.getDouble(0); la[k] = q.getDouble(1) }
                // Exclude long connectors that merely detour between almost coincident endpoints.
                if (props?.optBoolean("roadAccessUnverified") == true &&
                    props.optString("highway") == "network_connector" && props.optDouble("lengthM") > 160 &&
                    GeoPoint(la.first(), lo.first()).distanceToAsDouble(GeoPoint(la.last(), lo.last())) * 4 < props.optDouble("lengthM")) continue
                out.add(Line(la, lo))
            }
        }
        return out
    }

    fun profileFor(course: Course): List<PP> = pathsByCourse[course.code].orEmpty()

    private fun buildProfile(line: Line, reference: List<PP>): List<PP> {
        var distance = 0.0
        return line.lat.indices.map { i ->
            val point = GeoPoint(line.lat[i], line.lon[i])
            if (i > 0) distance += point.distanceToAsDouble(GeoPoint(line.lat[i - 1], line.lon[i - 1]))
            var best = Double.POSITIVE_INFINITY
            var elevation = reference.firstOrNull()?.e ?: 0f
            val longitudeScale = cos(Math.toRadians(point.latitude))
            for ((a, b) in reference.zipWithNext()) {
                val ax = (a.lon - point.longitude) * longitudeScale
                val ay = a.lat - point.latitude
                val dx = (b.lon - a.lon) * longitudeScale
                val dy = b.lat - a.lat
                val length = dx * dx + dy * dy
                if (length <= 0.0) continue
                val along = (-(ax * dx + ay * dy) / length).coerceIn(0.0, 1.0)
                val error = hypot(ax + along * dx, ay + along * dy)
                if (error < best) {
                    best = error
                    elevation = a.e + (b.e - a.e) * along.toFloat()
                }
            }
            PP(distance.toInt(), elevation, point.latitude, point.longitude)
        }
    }

    private fun readWalkingRoads(data: String): Pair<List<Road>, List<Road>> {
        val network = JSONObject(data)
        val nodes = network.getJSONObject("nodes")
        val ways = network.getJSONArray("ways")
        val paths = ArrayList<Road>()
        val streets = ArrayList<Road>()
        for (i in 0 until ways.length()) {
            val way = ways.getJSONObject(i)
            val path = way.optString("walk") == "yes"
            val foot = way.optString("foot")
            if (foot in setOf("no", "private", "use_sidepath")) continue
            val sidewalk = way.optString("sidewalk") in setOf("left", "right", "both", "yes")
            val sharedStreet = way.optString("hw") == "living_street"
            if (!path && !sidewalk && !sharedStreet && foot !in setOf("yes", "designated")) continue
            val ids = way.getJSONArray("nodes")
            if (ids.length() < 2) continue
            val points = (0 until ids.length()).map { nodes.getJSONArray(ids.getLong(it).toString()) }
            val name = way.optString("name")
            val road = Road(way.getLong("id"), name, name, way.getString("hw"), -1f,
                DoubleArray(points.size) { points[it].getDouble(1) },
                DoubleArray(points.size) { points[it].getDouble(0) })
            if (path) paths.add(road)
            streets.add(road)
        }
        return paths to streets
    }

    private fun parseContext(t: String) {
        val o = JSONObject(t)
        val sl = o.getJSONArray("slope"); val sg = ArrayList<Seg>(sl.length())
        for (i in 0 until sl.length()) {
            val e = sl.getJSONObject(i); val a = e.getJSONArray("a"); val b = e.getJSONArray("b")
            sg.add(Seg(a.getDouble(1), a.getDouble(0), b.getDouble(1), b.getDouble(0), e.getInt("grade")))
        }
        val ds = ArrayList<Dot>()
        o.getJSONArray("crosswalks").let { for (i in 0 until it.length()) it.getJSONObject(i).let { e -> ds.add(Dot(e.getDouble("lat"), e.getDouble("lon"), false)) } }
        o.optJSONArray("signals")?.let { for (i in 0 until it.length()) it.getJSONObject(i).let { e -> ds.add(Dot(e.getDouble("lat"), e.getDouble("lon"), true)) } }   // 미리보기에서만 보여줌
        segs = sg; dots = ds
    }

    /** 가는 길(점들)에서 m 미터 안에 있는 점인지. */
    private fun nearPath(pts: List<com.eudero.maplib.GeoPoint>, lat: Double, lon: Double, m: Double): Boolean {
        val k = 111000.0; val kx = k * cos(Math.toRadians(lat))
        for (p in pts) if (hypot((p.latitude - lat) * k, (p.longitude - lon) * kx) <= m) return true
        return false
    }
    fun courseCrossings(code: String): List<Dot> = crossingsByCourse[code].orEmpty()

    /** D1 코스 GeoJSON의 signal은 신호가 있는 횡단보도, osm은 현장에서 등록된 횡단 지점이다. */
    private fun readCrossings(json: String): List<Dot> {
        val features = JSONObject(json).getJSONArray("features")
        val result = mutableListOf<Dot>()
        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i)
            val props = feature.getJSONObject("properties")
            val layer = props.optString("layer")
            if (layer != "signal" && layer != "osm") continue
            val coords = feature.getJSONObject("geometry").getJSONArray("coordinates")
            val lat = coords.getDouble(1); val lon = coords.getDouble(0)
            val signal = layer == "signal" || props.optString("kind") == "signal"
            fun addIfNew(isSignal: Boolean) {
                val metersLon = 111320.0 * cos(Math.toRadians(lat))
                if (result.none { it.signal == isSignal && hypot((it.lat-lat)*111320.0, (it.lon-lon)*metersLon) < 5.0 })
                    result.add(Dot(lat, lon, isSignal))
            }
            addIfNew(false)
            if (signal) addIfNew(true)
        }
        return result
    }

    /** 미리보기용: 가는 길 주변 신호등. */
    fun signalsNear(route: List<com.eudero.maplib.GeoPoint>, m: Double = 50.0) = dots.filter { it.signal && nearPath(route, it.lat, it.lon, m) }
    /** 경사 데이터와 나란히 겹치는 구간을 코스의 중심선 좌표로 잘라낸다. */
    fun uphillOn(route: List<GeoPoint>, candidates: List<Seg> = segs): List<Seg> {
        if (route.size < 2) return emptyList()
        val metersLat = 111_320.0
        val metersLon = metersLat * cos(Math.toRadians(route.first().latitude))
        val tolerance = 2.0
        val minAlignment = cos(Math.toRadians(18.0))
        val bounds = doubleArrayOf(route.minOf { it.latitude } - tolerance / metersLat,
            route.maxOf { it.latitude } + tolerance / metersLat, route.minOf { it.longitude } - tolerance / metersLon,
            route.maxOf { it.longitude } + tolerance / metersLon)
        val nearby = candidates.filter { maxOf(it.aLat, it.bLat) >= bounds[0] && minOf(it.aLat, it.bLat) <= bounds[1] &&
            maxOf(it.aLon, it.bLon) >= bounds[2] && minOf(it.aLon, it.bLon) <= bounds[3] }
        val out = mutableListOf<Seg>()
        for ((a, b) in route.zipWithNext()) {
            val dx = (b.longitude - a.longitude) * metersLon
            val dy = (b.latitude - a.latitude) * metersLat
            val length = hypot(dx, dy)
            if (length < 0.01) continue
            val ux = dx / length
            val uy = dy / length
            for (s in nearby) {
                val ax = (s.aLon - a.longitude) * metersLon
                val ay = (s.aLat - a.latitude) * metersLat
                val sx = (s.bLon - s.aLon) * metersLon
                val sy = (s.bLat - s.aLat) * metersLat
                val projected = sx * ux + sy * uy
                if (kotlin.math.abs(projected) < minAlignment * hypot(sx, sy)) continue
                val offset = ux * ay - uy * ax
                val drift = ux * sy - uy * sx
                var t0 = 0.0
                var t1 = 1.0
                if (kotlin.math.abs(drift) < 0.00001) {
                    if (kotlin.math.abs(offset) > tolerance) continue
                } else {
                    val left = (-tolerance - offset) / drift
                    val right = (tolerance - offset) / drift
                    t0 = maxOf(0.0, minOf(left, right))
                    t1 = minOf(1.0, maxOf(left, right))
                    if (t1 <= t0) continue
                }
                val along = ax * ux + ay * uy
                val p = along + projected * t0
                val q = along + projected * t1
                val from = maxOf(0.0, minOf(p, q))
                val to = minOf(length, maxOf(p, q))
                if (to - from < 0.1) continue
                out += Seg(a.latitude + from * uy / metersLat, a.longitude + from * ux / metersLon,
                    a.latitude + to * uy / metersLat, a.longitude + to * ux / metersLon, s.grade)
            }
        }
        return out
    }

    /** 코스에서 반경 m 안에 있는 점인지. */
    private fun near(c: Course, lat: Double, lon: Double, m: Double): Boolean {
        val pad = m / 111000.0 * 1.5
        if (lat < c.minLat - pad || lat > c.maxLat + pad || lon < c.minLon - pad * 1.3 || lon > c.maxLon + pad * 1.3) return false
        val k = 111000.0; val kx = k * cos(Math.toRadians(lat))
        for (l in c.lines) for (i in l.lat.indices step 2) if (hypot((l.lat[i] - lat) * k, (l.lon[i] - lon) * kx) <= m) return true
        return false
    }

    class Layers(val segs: List<Seg>, val dots: List<Dot>)
    fun layersFor(c: Course, m: Double = 200.0, dotsM: Double = 14.0) = Layers(segs.filter { near(c, it.aLat, it.aLon, m) }, dots.filter { near(c, it.lat, it.lon, dotsM) })   // 신호등·횡단보도는 길 위에 있는 것만
}
