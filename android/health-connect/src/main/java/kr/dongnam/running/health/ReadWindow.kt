package kr.dongnam.running.health

import java.time.Duration
import java.time.Instant

internal fun validateReadWindow(start: Instant, end: Instant, now: Instant) {
    require(start.isBefore(end)) { "조회 시작은 종료보다 이전이어야 합니다." }
    require(!end.isAfter(now)) { "미래의 건강 기록은 조회할 수 없습니다." }
    require(!start.isBefore(now.minus(Duration.ofDays(7)))) {
        "이 연동은 최근 7일 기록만 읽습니다."
    }
}
