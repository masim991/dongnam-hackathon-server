package com.eudero.gano.data

import com.eudero.maplib.Course
import com.eudero.maplib.Line
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
    val dirs: List<Dir>, val layers: CourseRepo.Layers
) {
    fun isAtEntry(position: GeoPoint): Boolean = position.distanceToAsDouble(entry) <= GuidePlanner.ENTRY_RADIUS_M
}

object GuidePlanner {
    const val ENTRY_RADIUS_M = 20.0

    fun plan(course: Course, me: GeoPoint, demo: Boolean = false): Plan? {
        val profile = CourseRepo.profileFor(course)
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
        var route: Router.Approach? = null
        if (closest.offsetM > ENTRY_RADIUS_M) {
            route = Router.approach(CourseRepo.walkingRoads, me, candidates.map { projections[it].point })
            if (route != null) {
                segmentIndex = candidates[route.targetIndex]
            } else {
                // A riverside entrance or a crosswalk may join the course beyond the closest block.
                val roads = CourseRepo.approachRoads.ifEmpty { CourseRepo.walkingRoads }
                route = Router.approach(roads, me, projections.map { it.point })
                if (route != null) segmentIndex = route.targetIndex
            }
        }
        val join = projections[segmentIndex]
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
        val facilities = CourseRepo.courseCrossings(course.code)
        val dirs = ArrayList<Dir>()
        when {
            joinPoint.d - profile.first().d <= 5 -> dirs.add(Dir("방향 A", approach, forward, facilities))
            profile.last().d - joinPoint.d <= 5 -> dirs.add(Dir("방향 A", approach, backward, facilities))
            else -> {
                dirs.add(Dir("방향 A", approach, forward, facilities))
                // The demo's westbound crossing was reported on site; the feed has no coordinate for it.
                val observedCrossings = if (demo && course.code == "HD-BEACHPROM") 1 else 0
                dirs.add(Dir("방향 B", approach, backward, facilities, observedCrossings))
            }
        }
        val whole = Course(course.code, course.name, course.tagline, course.badge, course.lengthM,
            listOf(Line(DoubleArray(profile.size) { profile[it].lat }, DoubleArray(profile.size) { profile[it].lon })), course.region, true)
        return Plan(course, me, entry, approachM, dirs, CourseRepo.layersFor(whole))
    }
}
