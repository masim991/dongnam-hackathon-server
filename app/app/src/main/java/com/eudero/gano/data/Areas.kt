package com.eudero.gano.data

import com.eudero.maplib.Region

/** 지역 목록. 지금은 해운대구만. 지역을 늘리려면 여기에 추가하고 서버에 roads_<code>.json.gz / posts_<code>.json을 올리면 된다. */
data class Area(val code: String, val label: String, val lat: Double, val lon: Double, val region: Region)

object Areas {
    val HAEUNDAE = Area("haeundae", "해운대구", 35.1631, 129.1636, Region.BUSAN)
    val all = listOf(HAEUNDAE)
    var current: Area = HAEUNDAE
}
