package com.eudero.maplib

/** 추천 산책길 표시 상태(데이터). 실제 그리기는 KakaoOverlays가 카카오 지도 안에서 한다. */
class CourseOverlay {
    var courses: List<Course> = emptyList()
    var selected: Course? = null
    var visible = true
    var guiding = false
    var onCourseTap: ((Course) -> Unit)? = null
}
