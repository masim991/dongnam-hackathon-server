package kr.dongnam.running.health

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertThrows
import org.junit.Test

class ReadWindowTest {
    private val now = Instant.parse("2026-11-27T07:00:00Z")

    @Test fun acceptsSevenDayBoundary() {
        validateReadWindow(now.minus(Duration.ofDays(7)), now, now)
    }

    @Test fun rejectsOldDataAndInvalidIntervals() {
        assertThrows(IllegalArgumentException::class.java) {
            validateReadWindow(now.minus(Duration.ofDays(7)).minusSeconds(1), now, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateReadWindow(now, now, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateReadWindow(now, now.plusSeconds(1), now)
        }
    }
}
