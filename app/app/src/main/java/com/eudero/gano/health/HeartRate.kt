package com.eudero.gano.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Length
import com.eudero.gano.data.RunStore
import com.eudero.gano.data.SavedRun
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

object HeartRate {
    val permission = HealthPermission.getReadPermission(HeartRateRecord::class)
    private val readSteps = HealthPermission.getReadPermission(StepsRecord::class)
    val writeSteps = HealthPermission.getWritePermission(StepsRecord::class)
    private val writeRun = HealthPermission.getWritePermission(ExerciseSessionRecord::class)
    private val writeDistance = HealthPermission.getWritePermission(DistanceRecord::class)
    val permissions = setOf(permission, readSteps, writeRun, writeDistance, writeSteps)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    fun status(c: Context) = HealthConnectClient.getSdkStatus(c)
    private fun prefs(c: Context) = c.getSharedPreferences("health_sync", Context.MODE_PRIVATE)
    fun linked(c: Context) = prefs(c).getBoolean("enabled", false)
    suspend fun hasConnection(c: Context): Boolean =
        status(c) == HealthConnectClient.SDK_AVAILABLE &&
            HealthConnectClient.getOrCreate(c).permissionController.getGrantedPermissions().any { it in permissions }

    fun connect(c: Context, granted: Set<String>) {
        prefs(c).edit().putBoolean("enabled", granted.any { it in permissions }).apply()
        sync(c)
    }
    suspend fun latest(c: Context, startMs: Long): Pair<Int, String> {
        if (!linked(c) || status(c) != HealthConnectClient.SDK_AVAILABLE) return 0 to ""
        val client = HealthConnectClient.getOrCreate(c)
        if (permission !in client.permissionController.getGrantedPermissions()) return 0 to ""
        val now = Instant.now()
        val start = maxOf(Instant.ofEpochMilli(startMs), now.minusSeconds(600))
        if (start >= now) return 0 to ""
        val page = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(start, now),
            ascendingOrder = false, pageSize = 20))
        val sample = page.records.flatMap { it.samples }.filter { it.time >= start && it.time <= now }.maxByOrNull { it.time }
        return (sample?.beatsPerMinute?.toInt() ?: 0) to "Health Connect"
    }

    suspend fun steps(c: Context, since: Instant): Long? {
        val now = Instant.now()
        if (since >= now || !linked(c) || status(c) != HealthConnectClient.SDK_AVAILABLE) return null
        val client = HealthConnectClient.getOrCreate(c)
        if (readSteps !in client.permissionController.getGrantedPermissions()) return null
        return client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL),
            TimeRangeFilter.between(since, now)))[StepsRecord.COUNT_TOTAL] ?: 0L
    }

    suspend fun metrics(c: Context, r: SavedRun): Pair<Long?, Long?> {
        if (!r.healthEligible || !linked(c) || status(c) != HealthConnectClient.SDK_AVAILABLE) return r.phoneSteps to null
        val client = HealthConnectClient.getOrCreate(c)
        val grants = client.permissionController.getGrantedPermissions()
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(r.startMs), Instant.ofEpochMilli(r.endMs))
        val wanted = buildSet<androidx.health.connect.client.aggregate.AggregateMetric<*>> {
            if (readSteps in grants) add(StepsRecord.COUNT_TOTAL)
            if (permission in grants) add(HeartRateRecord.BPM_AVG)
        }
        val totals = if (wanted.isEmpty()) null else client.aggregate(AggregateRequest(wanted, range))
        return (totals?.get(StepsRecord.COUNT_TOTAL) ?: r.phoneSteps) to totals?.get(HeartRateRecord.BPM_AVG)
    }

    fun sync(context: Context) {
        val c = context.applicationContext
        if (!linked(c) || status(c) != HealthConnectClient.SDK_AVAILABLE) return
        scope.launch {
            mutex.withLock {
                try {
                    val client = HealthConnectClient.getOrCreate(c)
                    val grants = client.permissionController.getGrantedPermissions()
                    for (r in RunStore.list(c).filter { it.healthEligible }) write(c, client, grants, r)
                } catch (e: Exception) { android.util.Log.w("HealthSync", "Workout sync pending", e) }
            }
        }
    }

    private suspend fun write(c: Context, client: HealthConnectClient, grants: Set<String>, run: SavedRun) {
        val start = Instant.ofEpochMilli(run.startMs)
        val end = Instant.ofEpochMilli(run.endMs)
        if (end <= start) return
        val zone = ZoneId.systemDefault().rules
        val a = zone.getOffset(start)
        val b = zone.getOffset(end)
        val store = prefs(c)
        val records = mutableListOf<Record>()
        val keys = mutableListOf<String>()
        fun add(permission: String, suffix: String, make: (Metadata) -> Record) {
            val key = "run-${run.id}-$suffix"
            if (permission in grants && !store.getBoolean(key, false)) {
                records += make(Metadata.activelyRecorded(Device(type = Device.TYPE_PHONE), clientRecordId = key))
                keys += key
            }
        }
        add(writeRun, "session") { ExerciseSessionRecord(startTime = start, startZoneOffset = a, endTime = end, endZoneOffset = b, exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, exerciseRoute = null,
            title = run.title.ifBlank { "뛰고보자 러닝" }, metadata = it) }
        add(writeDistance, "distance") { DistanceRecord(start, a, end, b, Length.meters(run.distanceM), it) }
        run.phoneSteps?.takeIf { it > 0 }?.let { count ->
            add(writeSteps, "steps") { StepsRecord(start, a, end, b, count, it) }
        }
        if (records.isNotEmpty()) {
            client.insertRecords(records)
            store.edit().apply { keys.forEach { putBoolean(it, true) } }.apply()
        }
    }
}
