package com.eudero.maplib

class Line(val lat: DoubleArray, val lon: DoubleArray)

/** 추천 산책길. 여러 선(way)으로 이루어질 수 있다. */
class Course(val code: String, val name: String, val tagline: String, val badge: String, val lengthM: Int, val lines: List<Line>,
             val region: String = "부산", val detail: Boolean = false) {
    companion object {
        // Opaque, saturated inks against Kakao's pale land, water and park fills. Red is reserved for slopes.
        private val lineColors = intArrayOf(
            0xFF0071FF.toInt(), 0xFF91EF00.toInt(), 0xFFFFCE00.toInt(), 0xFF9435EF.toInt(),
            0xFFFF8700.toInt(), 0xFFF322AC.toInt(), 0xFF008E76.toInt(), 0xFF4145CD.toInt()
        )
        fun assignLineColors(courses: List<Course>) {
            // Neighbouring bounds include intersections, shared paths and parallel roads on the same screen.
            val neighbours = courses.associateWith { a -> courses.filter { b ->
                a !== b && a.minLat <= b.maxLat + 0.003 && b.minLat <= a.maxLat + 0.003 &&
                    a.minLon <= b.maxLon + 0.004 && b.minLon <= a.maxLon + 0.004
            } }
            val assigned = mutableMapOf<Course, Int>()
            val remaining = courses.sortedBy { it.code }.toMutableList()
            while (remaining.isNotEmpty()) {
                val next = remaining.maxWith(compareBy<Course>(
                    { neighbours.getValue(it).mapNotNull(assigned::get).distinct().size },
                    { neighbours.getValue(it).size }
                ))
                val used = neighbours.getValue(next).mapNotNull(assigned::get)
                val preferred = next.preferredColor
                val color = lineColors.indices.minWith(compareBy<Int>({ used.count { n -> n == it } },
                    { if (it == preferred) 0 else 1 }, { it }))
                next.lineColor = lineColors[color]
                assigned[next] = color
                remaining.remove(next)
            }
        }
    }
    private val preferredColor = when (code) {
        "HD-BEACHPROM" -> 0
        "HD-BEACHROAD" -> 1
        "HD-BEACHPROM-EXT", "HD-GALMAET" -> 2
        "HD-DALMAJI", "HD-DONGBAEK" -> 3
        "HD-MOONTAN" -> 4
        else -> Math.floorMod(code.hashCode(), lineColors.size)
    }
    var lineColor = lineColors[preferredColor]
        private set
    val minLat = lines.minOf { it.lat.min() }; val maxLat = lines.maxOf { it.lat.max() }
    val minLon = lines.minOf { it.lon.min() }; val maxLon = lines.maxOf { it.lon.max() }
}

class Seg(val aLat: Double, val aLon: Double, val bLat: Double, val bLon: Double, val grade: Int)
class Dot(val lat: Double, val lon: Double, val signal: Boolean)

/** A physical stretch of path used by several courses, ordered along its first course. */
internal class SharedPath(val courses: List<Course>, val points: MutableList<GeoPoint>)

private data class PathNode(val lat: Int, val lon: Int) {
    fun point() = GeoPoint(lat / 1e6, lon / 1e6)
}

private data class PathEdge(val a: PathNode, val b: PathNode)

internal fun sharedPaths(courses: List<Course>): List<SharedPath> {
    val nodesByCourse = courses.sortedBy { it.code }.associateWith { course ->
        course.lines.map { line -> line.lat.indices.map { i ->
            PathNode(Math.round(line.lat[i] * 1e6).toInt(), Math.round(line.lon[i] * 1e6).toInt())
        } }
    }
    val nodes = nodesByCourse.values.flatten().flatten().distinct().sortedBy { it.lat }
    // Split collinear edges at intermediate nodes so opposite directions and different sampling match.
    fun split(a: PathNode, b: PathNode): List<PathNode> {
        if (a == b) return emptyList()
        val dx = (b.lon - a.lon).toDouble()
        val dy = (b.lat - a.lat).toDouble()
        val length = kotlin.math.hypot(dx, dy)
        val south = minOf(a.lat, b.lat)
        val north = maxOf(a.lat, b.lat)
        val inserted = mutableListOf<Pair<Double, PathNode>>()
        var i = nodes.binarySearchBy(south - 1) { it.lat }.let { if (it < 0) -it - 1 else it }
        while (i < nodes.size && nodes[i].lat <= north) {
            val p = nodes[i++]
            if (p.lon !in minOf(a.lon, b.lon)..maxOf(a.lon, b.lon) || p == a || p == b) continue
            val x = p.lon - a.lon
            val y = p.lat - a.lat
            val along = (x * dx + y * dy) / length
            if (along > 0 && along < length && kotlin.math.abs(x * dy - y * dx) <= length)
                inserted += along to p
        }
        return listOf(a) + inserted.sortedBy { it.first }.map { it.second } + b
    }
    fun edge(a: PathNode, b: PathNode) =
        if (a.lat < b.lat || (a.lat == b.lat && a.lon < b.lon)) PathEdge(a, b) else PathEdge(b, a)
    val owners = HashMap<PathEdge, MutableSet<Course>>()
    val paths = nodesByCourse.mapValues { (course, lines) ->
        lines.map { line -> buildList<PathNode> {
            line.firstOrNull()?.let { add(it) }
            for ((a, b) in line.zipWithNext()) {
                var prev = a
                for (p in split(a, b).drop(1)) {
                    owners.getOrPut(edge(prev, p)) { linkedSetOf() }.add(course)
                    add(p); prev = p
                }
            }
        } }
    }
    val shared = mutableListOf<SharedPath>()
    for ((course, lines) in paths) for (line in lines) {
        var run: SharedPath? = null
        for ((a, b) in line.zipWithNext()) {
            val group = owners.getValue(edge(a, b))
            if (group.size < 2 || group.first() != course) { run = null; continue }
            if (run == null || run.courses != group.toList()) {
                run = SharedPath(group.toList(), mutableListOf(a.point()))
                shared.add(run)
            }
            run.points.add(b.point())
        }
    }
    return shared
}
