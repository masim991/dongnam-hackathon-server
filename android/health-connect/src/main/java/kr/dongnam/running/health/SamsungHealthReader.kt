package kr.dongnam.running.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant

data class RunningSession(
    val id: String,
    val start: Instant,
    val end: Instant,
    val title: String?,
)

data class SamsungActivitySummary(
    val start: Instant,
    val end: Instant,
    // Null means no shared records, rather than a measured zero.
    val steps: Long?,
    val distanceMeters: Double?,
    val runningSessions: List<RunningSession>,
)

/** Foreground, read-only access. Data stays on device; Samsung Health must share it first. */
class SamsungHealthReader(context: Context) {
    private val context = context.applicationContext

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    suspend fun hasPermissions(): Boolean {
        if (sdkStatus() != HealthConnectClient.SDK_AVAILABLE) return false
        return HealthConnectClient.getOrCreate(context).permissionController
            .getGrantedPermissions().containsAll(READ_PERMISSIONS)
    }

    // Re-check both availability and grants at each read: users may revoke access at any time.
    suspend fun readRecentSummary(days: Long = 7): SamsungActivitySummary {
        require(days in 1..7) { "조회 기간은 1~7일이어야 합니다." }
        val end = Instant.now()
        val start = end.minus(Duration.ofDays(days))
        validateReadWindow(start, end, end)
        check(sdkStatus() == HealthConnectClient.SDK_AVAILABLE) {
            "Health Connect를 사용할 수 없습니다. 설치 또는 업데이트를 확인해 주세요."
        }
        val client = HealthConnectClient.getOrCreate(context)
        if (!client.permissionController.getGrantedPermissions().containsAll(READ_PERMISSIONS)) {
            throw SecurityException("걸음 수·거리·운동 기록 읽기 권한이 필요합니다.")
        }
        val range = TimeRangeFilter.between(start, end)
        val origins = setOf(DataOrigin(SAMSUNG_HEALTH_PACKAGE))
        val totals = client.aggregate(AggregateRequest(
            metrics = setOf(StepsRecord.COUNT_TOTAL, DistanceRecord.DISTANCE_TOTAL),
            timeRangeFilter = range,
            dataOriginFilter = origins,
        ))
        val sessions = mutableListOf<RunningSession>()
        var token: String? = null
        do {
            val page = client.readRecords(ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = range,
                dataOriginFilter = origins,
                pageToken = token,
            ))
            sessions += page.records.filter {
                it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING ||
                    it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
            }.map { RunningSession(it.metadata.id, it.startTime, it.endTime, it.title) }
            token = page.pageToken
        } while (token != null)
        return SamsungActivitySummary(
            start, end, totals[StepsRecord.COUNT_TOTAL],
            totals[DistanceRecord.DISTANCE_TOTAL]?.inMeters, sessions,
        )
    }

    companion object {
        const val SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth"
        val READ_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        )

        fun permissionContract() = PermissionController.createRequestPermissionResultContract()
    }
}
