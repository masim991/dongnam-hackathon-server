package com.eudero.gano.data

import com.eudero.maplib.*
import org.junit.Assert.*
import org.junit.Test

class GuidePlannerTest {
    private fun line(vararg pts: GeoPoint) = Line(pts.map { it.latitude }.toDoubleArray(), pts.map { it.longitude }.toDoubleArray())
    private fun point(x: Int, y: Int) = GeoPoint(35.16 + y / 111195.0, 129.16 + x / 91000.0)
    private fun plan(points: List<GeoPoint>, from: GeoPoint, roads: List<Road> = emptyList()): Plan {
        var meters = 0.0
        val samples = points.mapIndexed { i, p ->
            if (i > 0) meters += points[i - 1].distanceToAsDouble(p)
            PP(meters.toInt(), 0f, p.latitude, p.longitude)
        }
        val course = Course("test", "test", "", "", meters.toInt(), listOf(line(*points.toTypedArray())), "부산", true)
        return GuidePlanner.calculate(course, from, samples, roads, roads, emptyList(), false)!!
    }

    @Test fun bothDirectionsStartAtTheSameEntryAndEndAtOppositeEnds() {
        val points = listOf(point(0,0), point(100,0), point(200,0))
        val guide = plan(points, points[1])
        assertEquals(listOf("방향 A", "방향 B"), guide.dirs.map { it.label })
        guide.dirs.zip(listOf(points.last(), points.first())).forEach { (dir, end) ->
            assertTrue(dir.path.first().distanceToAsDouble(guide.entry) < 0.1)
            assertTrue(dir.path.last().distanceToAsDouble(end) < 0.1)
        }
        assertEquals("방향 B", plan(points, points.last()).dirs.single().label)
        assertEquals("방향 A", plan(points, points.first()).dirs.single().label)
    }

    @Test fun midSegmentFootpathEntranceIsReachableInBothDirections() {
        val points = listOf(point(0,0), point(400,0))
        val start = point(80,120)
        val path = line(start, point(180,120), point(180,0))
        val road = Road(1,"","","footway",0f,path.lat,path.lon)
        val guide = plan(points, start, listOf(road))
        assertTrue(guide.approachM in 210..235)
        assertEquals(2, guide.dirs.size)
        guide.dirs.forEach { dir ->
            assertTrue(dir.approach.first().distanceToAsDouble(start) < 0.1)
            assertTrue(dir.approach.last().distanceToAsDouble(dir.coursePath.first()) < 0.1)
        }
        assertTrue(plan(points, start).dirs.all { it.approach.isEmpty() })
    }

    @Test fun shortReturnCanFinishAndLoopCannotFinishAtItsEntrance() {
        assertTrue(GuidePlanner.reachedFinish(48.0, 45.0, 4.0))
        assertFalse(GuidePlanner.reachedFinish(48.0, 10.0, 22.0))
        assertFalse(GuidePlanner.reachedFinish(980.0, 17.0, 4.0))
        assertTrue(GuidePlanner.reachedFinish(980.0, 960.0, 4.0))
    }

    @Test fun missingConnectionOnlyCountsTheMappedWalk() {
        val course = listOf(point(0,0), point(400,0))
        val origin = point(100,150)
        val start = point(165,150)
        val line = line(start, point(165,0))
        val road = Road(1, "", "", "footway", 0f, line.lat, line.lon)
        val hybrid = plan(course, origin, listOf(road))
        assertTrue(hybrid.connectorTarget!!.distanceToAsDouble(start) < 1)
        assertTrue(hybrid.approachM in 145..155)
        assertEquals(2, hybrid.dirs.size)
        assertEquals(hybrid.dirs[0].approach, hybrid.dirs[1].approach)
        val direct = plan(course, origin)
        assertEquals(0, direct.approachM)
        assertEquals(direct.entry, direct.connectorTarget)
    }

    @Test fun bothDirectionsMakeAFullLapFromTheJoin() {
        val points = listOf(point(0,0), point(100,0), point(100,100), point(0,100), point(0,0))
        for (start in listOf(points.first(), point(60,0))) {
            val guide = plan(points, start)
            assertEquals(2, guide.dirs.size)
            guide.dirs.forEach {
                assertTrue(it.courseM in 395..405)
                assertTrue(it.coursePath.first().distanceToAsDouble(start) < 0.1)
                assertTrue(it.coursePath.last().distanceToAsDouble(start) < 0.1)
            }
        }
    }
}
