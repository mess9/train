package train.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import train.client.ApiClient
import train.core.HealthRecord
import train.core.HealthType
import train.core.HealthUpload
import train.core.SleepStage
import train.core.SleepStageType
import java.time.Duration
import java.time.ZoneOffset
import kotlin.reflect.KClass
import kotlin.time.Instant

/** Записей в одном запросе на сервер: пульс тренировки — тысячи точек на запись. */
private const val UPLOAD_CHUNK = 200

/** Первая выгрузка — за столько дней; старше 30 дней нужна история (READ_HEALTH_DATA_HISTORY). */
private const val BACKFILL_DAYS = 730L

/** Типы записей, которые читаем: наш тип ↔ класс Health Connect. */
val SYNC_TYPES: Map<HealthType, KClass<out Record>> = linkedMapOf(
    HealthType.SLEEP to SleepSessionRecord::class,
    HealthType.STEPS to StepsRecord::class,
    HealthType.HEART_RATE to HeartRateRecord::class,
    HealthType.RESTING_HEART_RATE to RestingHeartRateRecord::class,
    HealthType.HRV to HeartRateVariabilityRmssdRecord::class,
    HealthType.SPO2 to OxygenSaturationRecord::class,
    HealthType.RESPIRATORY_RATE to RespiratoryRateRecord::class,
    HealthType.EXERCISE to ExerciseSessionRecord::class,
)

/** Все разрешения, которые просит экран: чтение типов, фон и история. */
val SYNC_PERMISSIONS: Set<String> = SYNC_TYPES.values.map { HealthPermission.getReadPermission(it) }.toSet() +
    HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND + HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

/**
 * Health Connect → сервер train. По каждому типу: первый раз — все записи за [BACKFILL_DAYS]
 * дней, дальше — только изменения по токену Health Connect. Сервер принимает повтор той же
 * записи без дублей, поэтому при сбое пачку можно отправить снова; токен сохраняется только
 * после успешной отправки.
 */
class HealthSync(private val context: Context) {
    private val settings = Settings(context)

    /** Итог одной строкой; ошибка сети или сервера — исключение, WorkManager повторит. */
    suspend fun run(): String {
        check(settings.configured) { "не задан сервер: вставьте ссылку …/#key=…" }
        check(HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) { "Health Connect недоступен" }
        val hc = HealthConnectClient.getOrCreate(context)
        val granted = hc.permissionController.getGrantedPermissions()
        val http = HttpClient(OkHttp) { followRedirects = false }
        try {
            val api = ApiClient(http, settings.url!!, settings.key, settings.headers)
            var saved = 0
            var deleted = 0
            val skipped = mutableListOf<String>()
            for ((type, cls) in SYNC_TYPES) {
                if (HealthPermission.getReadPermission(cls) !in granted) {
                    skipped += type.name.lowercase()
                    continue
                }
                val (s, d) = syncType(hc, api, type, cls)
                saved += s
                deleted += d
            }
            return "отправлено $saved, удалено $deleted" + if (skipped.isEmpty()) "" else " · нет разрешения: ${skipped.joinToString()}"
        } finally {
            http.close()
        }
    }

    private suspend fun syncType(hc: HealthConnectClient, api: ApiClient, type: HealthType, cls: KClass<out Record>): Pair<Int, Int> {
        val token = settings.token(type.name)
        if (token == null) {
            // Токен — до чтения: изменения во время выгрузки придут следующим разом
            val next = hc.getChangesToken(ChangesTokenRequest(setOf(cls)))
            val now = java.time.Instant.now()
            val filter = TimeRangeFilter.between(now.minus(Duration.ofDays(BACKFILL_DAYS)), now)
            var page: String? = null
            var saved = 0
            do {
                val r = hc.readRecords(ReadRecordsRequest(cls, filter, pageSize = 1000, pageToken = page))
                saved += upload(api, r.records.mapNotNull { it.toHealth() }, emptyList())
                page = r.pageToken
            } while (page != null)
            settings.setToken(type.name, next)
            return saved to 0
        }
        var current: String = token
        var saved = 0
        var deleted = 0
        while (true) {
            val r = hc.getChanges(current)
            if (r.changesTokenExpired) {
                // Токен живёт 30 дней: телефон долго был без сети — выгружаем заново, сервер не задвоит
                settings.setToken(type.name, null)
                return syncType(hc, api, type, cls).let { (s, d) -> (saved + s) to (deleted + d) }
            }
            val records = r.changes.filterIsInstance<UpsertionChange>().mapNotNull { it.record.toHealth() }
            val gone = r.changes.filterIsInstance<DeletionChange>().map { it.recordId }
            saved += upload(api, records, gone)
            deleted += gone.size
            current = r.nextChangesToken
            settings.setToken(type.name, current)
            if (!r.hasMore) break
        }
        return saved to deleted
    }

    private suspend fun upload(api: ApiClient, records: List<HealthRecord>, deleted: List<String>): Int {
        if (records.isEmpty() && deleted.isEmpty()) return 0
        var saved = 0
        val chunks = records.chunked(UPLOAD_CHUNK).ifEmpty { listOf(emptyList()) }
        chunks.forEachIndexed { i, chunk ->
            saved += api.uploadHealth(HealthUpload(chunk, if (i == 0) deleted else emptyList())).saved
        }
        return saved
    }
}

private fun java.time.Instant.k(): Instant = Instant.fromEpochSeconds(epochSecond, nano)

private fun ZoneOffset?.sec(): Int? = this?.totalSeconds

private fun stage(t: Int): SleepStageType = when (t) {
    SleepSessionRecord.STAGE_TYPE_AWAKE -> SleepStageType.AWAKE
    SleepSessionRecord.STAGE_TYPE_SLEEPING -> SleepStageType.SLEEPING
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> SleepStageType.OUT_OF_BED
    SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepStageType.LIGHT
    SleepSessionRecord.STAGE_TYPE_DEEP -> SleepStageType.DEEP
    SleepSessionRecord.STAGE_TYPE_REM -> SleepStageType.REM
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> SleepStageType.AWAKE_IN_BED
    else -> SleepStageType.UNKNOWN
}

/** Запись Health Connect → [HealthRecord]; типы, которых нет в [SYNC_TYPES], — `null`. */
fun Record.toHealth(): HealthRecord? {
    val id = metadata.id
    val source = metadata.dataOrigin.packageName
    val modified = metadata.lastModifiedTime.k()
    return when (this) {
        is StepsRecord -> HealthRecord(
            id, HealthType.STEPS, source, startTime.k(), endTime.k(), startZoneOffset.sec(), value = count.toDouble(), modifiedAt = modified,
        )
        is SleepSessionRecord -> HealthRecord(
            id, HealthType.SLEEP, source, startTime.k(), endTime.k(), startZoneOffset.sec(),
            stages = stages.map { SleepStage(it.startTime.k(), it.endTime.k(), stage(it.stage)) }.ifEmpty { null },
            title = title, modifiedAt = modified,
        )
        is HeartRateRecord -> HealthRecord(
            id, HealthType.HEART_RATE, source, startTime.k(), endTime.k(), startZoneOffset.sec(),
            hrSec = samples.map { (it.time.epochSecond - startTime.epochSecond).toInt() },
            hrBpm = samples.map { it.beatsPerMinute.toInt() },
            modifiedAt = modified,
        )
        is RestingHeartRateRecord -> HealthRecord(
            id, HealthType.RESTING_HEART_RATE, source, time.k(), zoneOffset = zoneOffset.sec(), value = beatsPerMinute.toDouble(), modifiedAt = modified,
        )
        is HeartRateVariabilityRmssdRecord -> HealthRecord(
            id, HealthType.HRV, source, time.k(), zoneOffset = zoneOffset.sec(), value = heartRateVariabilityMillis, modifiedAt = modified,
        )
        is OxygenSaturationRecord -> HealthRecord(
            id, HealthType.SPO2, source, time.k(), zoneOffset = zoneOffset.sec(), value = percentage.value, modifiedAt = modified,
        )
        is RespiratoryRateRecord -> HealthRecord(
            id, HealthType.RESPIRATORY_RATE, source, time.k(), zoneOffset = zoneOffset.sec(), value = rate, modifiedAt = modified,
        )
        is ExerciseSessionRecord -> HealthRecord(
            id, HealthType.EXERCISE, source, startTime.k(), endTime.k(), startZoneOffset.sec(),
            exerciseType = exerciseType, title = title, modifiedAt = modified,
        )
        else -> null
    }
}
