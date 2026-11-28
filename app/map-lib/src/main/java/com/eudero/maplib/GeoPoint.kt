package com.eudero.maplib

import kotlin.math.*

/** 위도·경도 한 점 (지도 라이브러리와 무관한 가벼운 값). */
class GeoPoint(val latitude: Double, val longitude: Double) {
    /** 두 점 사이 거리(미터, 구면 거리). */
    fun distanceToAsDouble(o: GeoPoint): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(latitude); val p2 = Math.toRadians(o.latitude)
        val dp = p2 - p1; val dl = Math.toRadians(o.longitude - longitude)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }
    override fun toString() = "$latitude,$longitude"
}
