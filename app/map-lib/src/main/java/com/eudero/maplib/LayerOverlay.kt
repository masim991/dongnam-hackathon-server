package com.eudero.maplib

/** 코스를 선택했을 때 켜지는 경사 구간 레이어(데이터). */
class LayerOverlay {
    var segs: List<Seg> = emptyList()
    var dots: List<Dot> = emptyList()   // 미리보기에서만 보이는 주변 신호등
    var showSlope = false
}
