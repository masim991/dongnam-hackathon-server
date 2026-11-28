package com.eudero.gano.data

import com.eudero.maplib.Course
import com.eudero.maplib.GeoPoint
import kotlin.math.cos
import kotlin.math.roundToInt

/** 길 안내 계획: 내 위치에서 가장 가까운 합류점으로 들어가서, 방향 A/B 중 골라 종점까지. 해운대만. */
class Dir(val label: String, val approach: List<GeoPoint>, val segment: List<PP>, facilities: List<com.eudero.maplib.Dot>, crossingMinimum: Int = 0) {
    val profile = CourseProfile(segment, facilities)
    val crossingSummary = "횡단보도 ${maxOf(crossingMinimum, profile.along.count { !it.dot.signal })}곳"
    val coursePath: List<GeoPoint> get() = segment.map { GeoPoint(it.lat, it.lon) }
    val path: List<GeoPoint> get() = approach.dropLast(1) + coursePath
    val courseM: Int get() = if (segment.size < 2) 0 else kotlin.math.abs(segment.last().d - segment.first().d)
    val ascentM: Int get() = segment.zipWithNext().sumOf { (a, b) -> maxOf(0f, b.e - a.e).toDouble() }.toInt()
    val chart: List<Pair<Int, Float>> get() = segment.map { kotlin.math.abs(it.d - segment.first().d) to it.e }
}

class Plan(
    val course: Course, val origin: GeoPoint, val entry: GeoPoint, val approachM: Int,
    val dirs: List<Dir>
) {
    val connectorTarget: GeoPoint? get() {
        if (isAtEntry(origin)) return null
        val first = dirs.first().approach.firstOrNull() ?: return entry
        return first.takeIf { origin.distanceToAsDouble(it) > 1.0 }
    }
    fun isAtEntry(position: GeoPoint): Boolean = position.distanceToAsDouble(entry) <= GuidePlanner.ENTRY_RADIUS_M
}

object GuidePlanner {
    const val ENTRY_RADIUS_M = 20.0

    internal fun reachedFinish(courseMeters: Double, traveledOnCourse: Double, distanceToEnd: Double): Boolean {
        if (courseMeters <= 0) return false
        val radius = (courseMeters / 4).coerceIn(3.0, 30.0)
        return distanceToEnd <= radius && traveledOnCourse >= minOf(courseMeters * 0.8, courseMeters - radius).coerceAtLeast(1.0)
    }

    fun plan(course: Course, me: GeoPoint, demo: Boolean = false): Plan? =
        calculate(course, me, CourseRepo.profileFor(course), CourseRepo.walkingRoads, CourseRepo.approachRoads,
            CourseRepo.courseCrossings(course.code), demo)

    internal fun calculate(course: Course, me: GeoPoint, profile: List<PP>, walking: List<com.eudero.maplib.Road>,
                           access: List<com.eudero.maplib.Road>, facilities: List<com.eudero.maplib.Dot>, demo: Boolean): Plan? {
        if (profile.size < 2) return null

        val points = profile.map { GeoPoint(it.lat, it.lon) }
        val longitudeScale = cos(Math.toRadians(me.latitude))
        val projections = points.zipWithNext { a, b -> project(me, a, b, longitudeScale) }
        val nearestSegment = projections.indices.minBy { projections[it].offsetM }
        val closest = projections[nearestSegment]
        val candidates = if (closest.offsetM <= ENTRY_RADIUS_M || demo) listOf(nearestSegment) else {
            projections.indices.filter {
                kotlin.math.abs(profile[it].d - profile[nearestSegment].d) <= 180 &&
                    projections[it].offsetM <= closest.offsetM + 120.0
            }
        }
        var segmentIndex = nearestSegment
        var join = closest
        var route: Router.Approach? = null
        if (closest.offsetM > ENTRY_RADIUS_M) {
            route = Router.approach(walking, me, candidates.map { projections[it].point })
            if (route != null) {
                segmentIndex = candidates[route.targetIndex]
                join = projections[segmentIndex]
            } else {
                // A riverside entrance or a crosswalk may join the course beyond the closest block.
                val roads = access.ifEmpty { walking }
                // A footpath can meet the middle of a long course segment. Sample the entire
                // line at less than the allowed 15 m road snap, retaining its profile position.
                val entrances = buildList {
                    for (i in projections.indices) {
                        add(i to projections[i])
                        val a = points[i]; val b = points[i + 1]
                        val count = kotlin.math.ceil(a.distanceToAsDouble(b) / 12.0).toInt().coerceAtLeast(1)
                        for (j in 0 until count) {
                            val f = j.toDouble() / count
                            val pt = GeoPoint(a.latitude + (b.latitude - a.latitude) * f,
                                a.longitude + (b.longitude - a.longitude) * f)
                            add(i to Projection(pt, f, me.distanceToAsDouble(pt)))
                        }
                    }
                    add(points.lastIndex - 1 to Projection(points.last(), 1.0, me.distanceToAsDouble(points.last())))
                }.sortedBy { it.second.offsetM }
                val targets = entrances.map { it.second.point }
                route = Router.approach(roads, me, targets, alternateM = 15.0)
                    ?: Router.approach(roads, me, targets, alternateM = 15.0, nearbyEntrance = true)
                        ?.takeIf { it.points.size >= 2 }
                if (route != null) {
                    val entrance = entrances[route.targetIndex]
                    segmentIndex = entrance.first
                    join = entrance.second
                }
            }
        }
        val entry = join.point
        val before = profile[segmentIndex]
        val after = profile[segmentIndex + 1]
        val joinPoint = PP(
            (before.d + (after.d - before.d) * join.fraction).roundToInt(),
            before.e + (after.e - before.e) * join.fraction.toFloat(), entry.latitude, entry.longitude
        )
        val forward = if (join.fraction == 1.0) profile.drop(segmentIndex + 1)
            else listOf(joinPoint) + profile.drop(segmentIndex + 1)
        val backward = if (join.fraction == 0.0) profile.take(segmentIndex + 1).asReversed()
            else listOf(joinPoint) + profile.take(segmentIndex + 1).asReversed()
        val approach = route?.points.orEmpty()
        val approachM = approach.zipWithNext { a, b -> a.distanceToAsDouble(b) }.sum().roundToInt()
        val dirs = ArrayList<Dir>()
        when {
            points.first().distanceToAsDouble(points.last()) <= 2 && profile.last().d - profile.first().d > 50 -> {
                // Rotate the lap to the actual entrance; distances must stay monotonic across the seam.
                val lap = forward + backward.asReversed().drop(1)
                var meters = 0.0
                val ordered = lap.mapIndexed { i, point ->
                    if (i > 0) meters += GeoPoint(lap[i-1].lat, lap[i-1].lon).distanceToAsDouble(GeoPoint(point.lat, point.lon))
                    PP(meters.roundToInt(), point.e, point.lat, point.lon)
                }
                dirs.add(Dir("방향 A", approach, ordered, facilities))
                dirs.add(Dir("방향 B", approach, ordered.asReversed(), facilities))
            }
            joinPoint.d - profile.first().d <= 5 -> dirs.add(Dir("방향 A", approach, forward, facilities))
            profile.last().d - joinPoint.d <= 5 -> dirs.add(Dir("방향 B", approach, backward, facilities))
            else -> {
                dirs.add(Dir("방향 A", approach, forward, facilities))
                // The demo's westbound crossing was reported on site; the feed has no coordinate for it.
                val observedCrossings = if (demo && course.code == "HD-BEACHPROM") 1 else 0
                dirs.add(Dir("방향 B", approach, backward, facilities, observedCrossings))
            }
        }
        return Plan(course, me, entry, approachM, dirs)
    }
}
