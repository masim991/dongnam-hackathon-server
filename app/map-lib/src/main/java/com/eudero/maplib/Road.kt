package com.eudero.maplib

/** 지도에 그리는 길 하나. lat/lon은 같은 길이의 배열. */
class Road(
    val id: Long, val name: String, val rawName: String, val hw: String, val slope: Float,
    val lat: DoubleArray, val lon: DoubleArray, val region: Region = Region.BUSAN
) {
    val minLat = lat.min(); val maxLat = lat.max(); val minLon = lon.min(); val maxLon = lon.max()
    val major get() = hw in MAJOR
    val walk get() = hw in WALK
    companion object {
        val MAJOR = setOf("primary", "secondary", "trunk", "tertiary")
        val WALK = setOf("footway", "path", "pedestrian", "steps", "track", "cycleway", "living_street")
    }
}

/** 부울경 색 구분(구조만 먼저). */
enum class Region(val label: String, val color: Int) {
    BUSAN("부산", 0xFF1E6FD9.toInt()), ULSAN("울산", 0xFF2E9E5B.toInt()), GYEONGNAM("경남", 0xFF8E44AD.toInt())
}

/** kind: 0 다른 사람(연두), 1 친구(연두), 2 나(흰색), 3 경고(빨강) */
class Pin(val id: String, val lat: Double, val lon: Double, val warn: Boolean, val payload: Any? = null, val kind: Int = 0, val icon: android.graphics.Bitmap? = null)
