package com.eudero.gano.data

import com.eudero.maplib.GeoPoint
import com.eudero.maplib.Line
import java.util.PriorityQueue
import kotlin.math.roundToInt

/** A display/guide line may continue only through actual shared coordinates. */
internal object TrailGeometry {
    private data class Key(val lat: Int, val lon: Int)
    private data class Visit(val index: Int, val distance: Double)
    private data class Search(val end: Int, val distance: Double, val previous: IntArray)

    fun mainLine(parts: List<Line>, reference: List<PP>, closedCourse: Boolean): Line? {
        if (parts.isEmpty()) return null
        if (parts.size == 1) return parts.first()
        val points = mutableListOf<GeoPoint>()
        val neighbors = mutableListOf<LinkedHashSet<Int>>()
        val ids = mutableMapOf<Key, Int>()
        fun node(lat: Double, lon: Double): Int = ids.getOrPut(Key((lat * 1e5).roundToInt(), (lon * 1e5).roundToInt())) {
            points += GeoPoint(lat, lon)
            neighbors.add(linkedSetOf())
            points.lastIndex
        }
        for (line in parts) {
            var previous = node(line.lat[0], line.lon[0])
            for (i in 1 until line.lat.size) {
                val next = node(line.lat[i], line.lon[i])
                if (next == previous) continue
                neighbors[previous].add(next)
                neighbors[next].add(previous)
                previous = next
            }
        }
        if (points.size < 2) return null
        val oldStart = reference.firstOrNull()?.let { GeoPoint(it.lat, it.lon) }
        val oldEnd = reference.lastOrNull()?.let { GeoPoint(it.lat, it.lon) }

        fun order(indices: List<Int>): Line {
            val forward = oldStart == null || oldEnd == null ||
                points[indices.first()].distanceToAsDouble(oldStart) + points[indices.last()].distanceToAsDouble(oldEnd) <=
                points[indices.last()].distanceToAsDouble(oldStart) + points[indices.first()].distanceToAsDouble(oldEnd)
            val idsInOrder = if (forward) indices else indices.asReversed()
            return Line(DoubleArray(idsInOrder.size) { points[idsInOrder[it]].latitude },
                DoubleArray(idsInOrder.size) { points[idsInOrder[it]].longitude })
        }

        if (closedCourse) {
            // Peel off dead-end spurs so an existing loop can remain a complete loop.
            val degree = IntArray(points.size) { neighbors[it].size }
            val ends = ArrayDeque<Int>()
            degree.indices.filter { degree[it] == 1 }.forEach(ends::addLast)
            while (ends.isNotEmpty()) {
                val i = ends.removeFirst()
                if (degree[i] != 1) continue
                degree[i] = 0
                for (next in neighbors[i]) if (degree[next] > 0 && --degree[next] == 1) ends.addLast(next)
            }
            val start = degree.indices.filter { degree[it] == 2 }
                .minByOrNull { i -> oldStart?.distanceToAsDouble(points[i]) ?: i.toDouble() }
            if (start != null) {
                val ordered = mutableListOf(start)
                var previous = -1
                var next: Int = start
                do {
                    val edges = neighbors[next].filter { degree[it] > 0 && it != previous }
                    if (degree[next] != 2 || edges.isEmpty()) break
                    previous = next
                    next = edges.first()
                    ordered.add(next)
                } while (next != start && ordered.size <= points.size)
                if (next == start && ordered.size >= 4) return order(ordered)
            }
        }

        val seen = BooleanArray(points.size)
        fun farthest(from: Int): Search {
            val queue = PriorityQueue<Visit>(compareBy { it.distance })
            val distance = DoubleArray(points.size) { Double.POSITIVE_INFINITY }
            val previous = IntArray(points.size) { -1 }
            distance[from] = 0.0
            queue.add(Visit(from, 0.0))
            var farthest = from
            while (queue.isNotEmpty()) {
                val (i, meters) = queue.remove()
                if (meters != distance[i]) continue
                seen[i] = true
                farthest = i
                for (next in neighbors[i]) {
                    val value = meters + points[i].distanceToAsDouble(points[next])
                    if (value < distance[next]) {
                        distance[next] = value
                        previous[next] = i
                        queue.add(Visit(next, value))
                    }
                }
            }
            return Search(farthest, distance[farthest], previous)
        }

        var best: Search? = null
        for (i in points.indices) {
            if (seen[i]) continue
            val far = farthest(farthest(i).end)
            if (far.distance > (best?.distance ?: 0.0)) best = far
        }
        val chosen = best ?: return null
        val sequence = generateSequence(chosen.end) { chosen.previous[it].takeIf { next -> next >= 0 } }.toList()
        return order(sequence)
    }
}
