package com.eudero.gano.data

import com.eudero.maplib.GeoPoint
import com.eudero.maplib.Road
import java.util.PriorityQueue
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong

/** 합류점은 길의 꼭짓점 사이에도 있을 수 있다. */
internal class Projection(val point: GeoPoint, val fraction: Double, val offsetM: Double)

internal fun project(point: GeoPoint, start: GeoPoint, end: GeoPoint, longitudeScale: Double): Projection {
    val x = (end.longitude - start.longitude) * longitudeScale
    val y = end.latitude - start.latitude
    val px = (point.longitude - start.longitude) * longitudeScale
    val py = point.latitude - start.latitude
    val lengthSquared = x * x + y * y
    val fraction = if (lengthSquared == 0.0) 0.0 else ((x * px + y * py) / lengthSquared).coerceIn(0.0, 1.0)
    return Projection(
        GeoPoint(start.latitude + y * fraction, start.longitude + (end.longitude - start.longitude) * fraction),
        fraction, hypot(px - x * fraction, py - y * fraction) * 111195.0
    )
}

object Router {
    class Approach(val targetIndex: Int, val points: List<GeoPoint>)

    private data class Edge(val start: Int, val end: Int, val length: Double)
    private data class Snap(val edge: Edge, val projection: Projection) {
        fun costTo(node: Int): Double = projection.offsetM +
            edge.length * (if (node == edge.start) projection.fraction else 1.0 - projection.fraction)
    }
    private data class Visit(val node: Int, val distance: Double)

    private var builtFor: List<Road>? = null
    private val nodes = ArrayList<GeoPoint>()
    private val adjacency = ArrayList<MutableList<Edge>>()
    private val edges = ArrayList<Edge>()

    private fun build(roads: List<Road>) {
        if (builtFor === roads) return
        val indices = HashMap<Pair<Long, Long>, Int>()
        nodes.clear()
        adjacency.clear()
        edges.clear()
        fun node(lat: Double, lon: Double): Int =
            indices.getOrPut((lat * 1e6).roundToLong() to (lon * 1e6).roundToLong()) {
                nodes.add(GeoPoint(lat, lon))
                adjacency.add(ArrayList())
                nodes.lastIndex
            }
        for (road in roads) {
            var start = node(road.lat[0], road.lon[0])
            for (i in 1 until road.lat.size) {
                val end = node(road.lat[i], road.lon[i])
                if (start == end) continue
                val edge = Edge(start, end, nodes[start].distanceToAsDouble(nodes[end]))
                adjacency[start].add(edge)
                adjacency[end].add(edge)
                edges.add(edge)
                start = end
            }
        }
        builtFor = roads
    }

    private fun nearby(point: GeoPoint, maxOffsetM: Double): List<Snap> {
        val scale = cos(Math.toRadians(point.latitude))
        val latMargin = maxOffsetM / 111195.0
        val lonMargin = latMargin / scale
        val candidates = ArrayList<Snap>()
        for (edge in edges) {
            val a = nodes[edge.start]
            val b = nodes[edge.end]
            if (point.latitude < minOf(a.latitude, b.latitude) - latMargin ||
                point.latitude > maxOf(a.latitude, b.latitude) + latMargin ||
                point.longitude < minOf(a.longitude, b.longitude) - lonMargin ||
                point.longitude > maxOf(a.longitude, b.longitude) + lonMargin) continue
            val projection = project(point, a, b, scale)
            if (projection.offsetM <= maxOffsetM) candidates.add(Snap(edge, projection))
        }
        if (candidates.isEmpty()) return emptyList()
        val closest = candidates.minOf { it.projection.offsetM }
        return candidates.filter { it.projection.offsetM <= closest + 1.5 }
            .sortedBy { it.projection.offsetM }.take(6)
    }

    /** 한 번의 탐색으로 주변 합류점들을 비교한다. 연결되지 않은 구간에는 안내선을 그리지 않는다. */
    @Synchronized fun approach(roads: List<Road>, from: GeoPoint, targets: List<GeoPoint>): Approach? {
        if (roads.isEmpty()) return null
        build(roads)
        val origins = nearby(from, 30.0)
        if (origins.isEmpty()) return null
        val destinations = targets.map { nearby(it, 15.0) }
        if (destinations.all { it.isEmpty() }) return null

        val distances = DoubleArray(nodes.size) { Double.POSITIVE_INFINITY }
        val previous = IntArray(nodes.size) { -1 }
        val entrySnaps = arrayOfNulls<Snap>(nodes.size)
        val queue = PriorityQueue<Visit>(compareBy { it.distance })
        for (origin in origins) {
            for (node in listOf(origin.edge.start, origin.edge.end)) {
                val distance = origin.costTo(node)
                if (distance >= distances[node]) continue
                distances[node] = distance
                entrySnaps[node] = origin
                queue.add(Visit(node, distance))
            }
        }
        while (queue.isNotEmpty()) {
            val (node, distance) = queue.remove()
            if (distance > distances[node]) continue
            for (edge in adjacency[node]) {
                val next = if (edge.start == node) edge.end else edge.start
                val cost = distance + edge.length
                if (cost < distances[next]) {
                    distances[next] = cost
                    previous[next] = node
                    entrySnaps[next] = entrySnaps[node]
                    queue.add(Visit(next, cost))
                }
            }
        }

        var best = Double.POSITIVE_INFINITY
        var target = -1
        var last = -1
        var startSnap: Snap? = null
        var endSnap: Snap? = null
        fun consider(cost: Double, i: Int, node: Int, origin: Snap?, destination: Snap) {
            if (cost >= best || origin == null) return
            best = cost
            target = i
            last = node
            startSnap = origin
            endSnap = destination
        }
        for ((i, snaps) in destinations.withIndex()) {
            for (destination in snaps) {
                val (start, end) = destination.edge
                consider(distances[start] + destination.costTo(start), i, start, entrySnaps[start], destination)
                consider(distances[end] + destination.costTo(end), i, end, entrySnaps[end], destination)
                for (origin in origins) if (destination.edge === origin.edge) {
                    val cost = origin.projection.offsetM + destination.projection.offsetM +
                        origin.projection.point.distanceToAsDouble(destination.projection.point)
                    consider(cost, i, -1, origin, destination)
                }
            }
        }
        val start = startSnap ?: return null
        val end = endSnap ?: return null
        val path = ArrayList<GeoPoint>()
        while (last >= 0) {
            path.add(nodes[last])
            last = previous[last]
        }
        path.reverse()
        val points = listOf(from, start.projection.point) + path + listOf(end.projection.point, targets[target])
        return Approach(target, points.filterIndexed { i, p -> i == 0 || p.distanceToAsDouble(points[i - 1]) > 0.01 })
    }
}
