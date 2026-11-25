package train.app

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import train.client.ApiClient
import train.client.ApiException
import train.core.HealthRecord
import train.core.HealthType
import train.core.HealthUpload
import train.core.SleepStage
import train.core.SleepStageType
import java.io.IOException
import java.text.DateFormat
import java.time.Duration
import java.time.ZoneOffset
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.time.Instant

// Сон, пульс, шаги из Health Connect → сервер (ADR 0015). Ключ, сервер и токен Cloudflare —
// те же, что у приложения; токены изменений и итог — в DeviceStorage рядом с ними

private const val STATUS_KEY = "health_status"
private fun tokenKey(type: HealthType) = "health_token_${type.name}"

/** Записей в одном запросе на сервер: пульс тренировки — тысячи точек на запись. */
private const val UPLOAD_CHUNK = 200

/** Первая выгрузка — за столько дней; старше 30 дней нужна история (READ_HEALTH_DATA_HISTORY). */
private const val BACKFILL_DAYS = 730L

/** Типы записей, которые читаем: наш тип ↔ класс Health Connect. */
private val SYNC_TYPES: Map<HealthType, KClass<out Record>> = linkedMapOf(
    HealthType.SLEEP to SleepSessionRecord::class,
    HealthType.STEPS to StepsRecord::class,
    HealthType.HEART_RATE to HeartRateRecord::class,
    HealthType.RESTING_HEART_RATE to RestingHeartRateRecord::class,
    HealthType.HRV to HeartRateVariabilityRmssdRecord::class,
    HealthType.SPO2 to OxygenSaturationRecord::class,
    HealthType.RESPIRATORY_RATE to RespiratoryRateRecord::class,
    HealthType.EXERCISE to ExerciseSessionRecord::class,
)

/** Все разрешения, которые просит приложение: чтение типов, фон и история. */
internal val HEALTH_PERMISSIONS: Set<String> = SYNC_TYPES.values.map { HealthPermission.getReadPermission(it) }.toSet() +
    HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND + HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

actual val healthSupported: Boolean = true

private fun available(c: Context) = HealthConnectClient.getSdkStatus(c) == HealthConnectClient.SDK_AVAILABLE

actual suspend fun healthState(): HealthState {
    val c = AndroidHost.context
    val status = DeviceStorage.read(STATUS_KEY) ?: "ещё не отправляли"
    if (!available(c)) return HealthState(listOf("Health Connect на этом телефоне недоступен", status), missing = false)
    val granted = runCatching { HealthConnectClient.getOrCreate(c).permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
    val missing = HEALTH_PERMISSIONS - granted
    val perms = if (missing.isEmpty()) "Доступ: весь" else "Доступ: нет ${missing.size} из ${HEALTH_PERMISSIONS.size} — " +
        missing.joinToString { it.substringAfterLast('.').lowercase() }
    return HealthState(listOf(perms, status), missing = missing.isNotEmpty())
}

actual fun healthAsk() {
    val c = AndroidHost.context
    when (HealthConnectClient.getSdkStatus(c)) {
        HealthConnectClient.SDK_AVAILABLE -> AndroidHost.askHealth(HEALTH_PERMISSIONS)
        // Android 13 и раньше: Health Connect — отдельное приложение, его надо поставить или обновить
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> AndroidHost.activity?.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")),
        )
        else -> DeviceStorage.write(STATUS_KEY, "Health Connect на этом телефоне недоступен")
    }
}

actual fun healthSend(afterWorkout: Boolean) {
    if (DeviceStorage.read(KEY_KEY).isNullOrBlank()) return
    HealthWorker.schedule(AndroidHost.context)
    HealthWorker.now(AndroidHost.context, afterWorkout)
}

/** Фон раз в [PERIOD_HOURS] часов — с запуска приложения, если оно подключено. */
internal fun healthSchedule(c: Context) {
    if (!DeviceStorage.read(KEY_KEY).isNullOrBlank() && available(c)) HealthWorker.schedule(c)
}

private val running = Mutex()

/**
 * Health Connect → сервер train. По каждому типу: первый раз — все записи за [BACKFILL_DAYS]
 * дней, дальше — только изменения по токену Health Connect. Сервер принимает повтор той же
 * записи без дублей, поэтому при сбое пачку можно отправить снова; токен сохраняется только
 * после успешной отправки.
 */
private class HealthSync(private val context: Context) {
    /** Сколько интервалов по минуте пропущено из-за битых записей (см. [readRange]). */
    private var broken = 0

    /**
     * Итог одной строкой; ошибка сети или сервера — исключение, WorkManager повторит. Запуски по
     * кнопке и по расписанию идут по очереди: иначе оба выгружают одно и то же.
     */
    suspend fun run(): String = running.withLock { sync() }

    private suspend fun sync(): String {
        val origin = serverOrigin()
        val key = DeviceStorage.read(KEY_KEY)
        check(origin.isNotEmpty() && !key.isNullOrBlank()) { "приложение не подключено к серверу" }
        check(available(context)) { "Health Connect недоступен" }
        val hc = HealthConnectClient.getOrCreate(context)
        val granted = hc.permissionController.getGrantedPermissions()
        // Свой клиент без кэша: кэш OkHttp на диске — один на процесс, он у клиента приложения
        val http = HttpClient(OkHttp) {
            followRedirects = false
            engine { addInterceptor(accessInterceptor) }
        }
        try {
            val api = ApiClient(http, origin, key)
            var saved = 0
            var deleted = 0
            val skipped = mutableListOf<String>()
            for ((type, cls) in SYNC_TYPES) {
                if (HealthPermission.getReadPermission(cls) !in granted) {
                    skipped += type.name.lowercase()
                    continue
                }
                // Первая выгрузка — минуты: настройки показывают, на каком типе она сейчас
                DeviceStorage.write(STATUS_KEY, "идёт отправка: ${type.name.lowercase()}, отправлено $saved")
                val (s, d) = syncType(hc, api, type, cls)
                saved += s
                deleted += d
            }
            return "отправлено $saved, удалено $deleted" +
                (if (broken == 0) "" else " · пропущено битых записей: $broken") +
                if (skipped.isEmpty()) "" else " · нет доступа: ${skipped.joinToString()}"
        } finally {
            http.close()
        }
    }

    private suspend fun syncType(hc: HealthConnectClient, api: ApiClient, type: HealthType, cls: KClass<out Record>): Pair<Int, Int> {
        val token = DeviceStorage.read(tokenKey(type)) ?: return backfill(hc, api, type, cls, Duration.ofDays(BACKFILL_DAYS))
        var current: String = token
        var saved = 0
        var deleted = 0
        while (true) {
            val r = try {
                hc.getChanges(current)
            } catch (_: IllegalArgumentException) {
                // В изменениях битая запись — пропустить её поштучно нельзя. Перечитываем срок жизни
                // токена по интервалам с новым токеном: она уже в прошлом и больше не придёт
                return backfill(hc, api, type, cls, Duration.ofDays(30)).let { (s, d) -> (saved + s) to (deleted + d) }
            }
            if (r.changesTokenExpired) {
                // Токен живёт 30 дней: телефон долго был без сети — выгружаем заново, сервер не задвоит
                DeviceStorage.remove(tokenKey(type))
                return syncType(hc, api, type, cls).let { (s, d) -> (saved + s) to (deleted + d) }
            }
            val records = r.changes.filterIsInstance<UpsertionChange>().mapNotNull { it.record.toHealth() }
            val gone = r.changes.filterIsInstance<DeletionChange>().map { it.recordId }
            saved += upload(api, records, gone)
            deleted += gone.size
            current = r.nextChangesToken
            DeviceStorage.write(tokenKey(type), current)
            if (!r.hasMore) break
        }
        return saved to deleted
    }

    /** Все записи типа за [period] до сейчас, затем новый токен изменений. */
    private suspend fun backfill(hc: HealthConnectClient, api: ApiClient, type: HealthType, cls: KClass<out Record>, period: Duration): Pair<Int, Int> {
        // Токен — до чтения: изменения во время выгрузки придут следующим разом
        val next = hc.getChangesToken(ChangesTokenRequest(setOf(cls)))
        val now = java.time.Instant.now()
        var saved = 0
        readRange(hc, cls, now.minus(period), now) { saved += upload(api, it.mapNotNull { r -> r.toHealth() }, emptyList()) }
        DeviceStorage.write(tokenKey(type), next)
        return saved to 0
    }

    /**
     * Записи за [from]..[to] пачками в [sink]. Jetpack-библиотека не читает запись, у которой
     * начало не раньше конца (такие пишет сам Android — шаги телефона), и роняет всю страницу.
     * Тогда интервал делится пополам, пока битая не останется одна в минуте, — эта минута
     * пропускается. Пачку отдаём, только когда интервал прочитан целиком: без повторов.
     */
    private suspend fun readRange(
        hc: HealthConnectClient,
        cls: KClass<out Record>,
        from: java.time.Instant,
        to: java.time.Instant,
        sink: suspend (List<Record>) -> Unit,
    ) {
        val records = try {
            buildList<Record> {
                var page: String? = null
                do {
                    val r = hc.readRecords(ReadRecordsRequest(cls, TimeRangeFilter.between(from, to), pageSize = 1000, pageToken = page))
                    addAll(r.records)
                    page = r.pageToken
                } while (page != null)
            }
        } catch (_: IllegalArgumentException) {
            if (Duration.between(from, to) <= Duration.ofMinutes(1)) {
                broken++
                return
            }
            val mid = from.plus(Duration.between(from, to).dividedBy(2))
            readRange(hc, cls, from, mid, sink)
            readRange(hc, cls, mid, to, sink)
            return
        }
        records.chunked(1000).forEach { sink(it) }
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

private const val PERIODIC = "health-sync"
private const val NOW = "health-sync-now"
private const val LATER = "health-sync-after-workout"

// Сон пишется раз в сутки, шаги и пульс — к вечеру: трёх раз в сутки хватает
private const val PERIOD_HOURS = 8L

/** Браслет отдаёт пульс тренировки в Health Connect не сразу: после тренировки — ещё раз через столько минут. */
private const val AFTER_WORKOUT_MIN = 30L

/** Отправка в фоне: раз в [PERIOD_HOURS] часов при сети, по кнопке и после тренировки. */
class HealthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
        fun status(s: String) = DeviceStorage.write(STATUS_KEY, "$time · $s")
        return try {
            status(HealthSync(applicationContext).run())
            Result.success()
        } catch (e: CancellationException) {
            // Android остановил задачу (новое нажатие, экономия батареи): готовые типы сохранены,
            // следующий запуск продолжит с того, на котором остановились
            status("прервано системой — продолжит при следующем запуске или по кнопке")
            throw e
        } catch (e: IOException) {
            status("нет связи: ${e.message ?: e::class.simpleName}")
            Result.retry()
        } catch (e: ApiException) {
            status(
                when (e.status) {
                    401 -> "сервер не принял ключ — подключите приложение заново"
                    404 -> "на сервере нет /api/health — он ещё не обновлён"
                    else -> "сервер ответил ${e.status}: ${e.message.orEmpty().take(200)}"
                },
            )
            Result.failure()
        } catch (e: Exception) {
            // Нет подключения, Health Connect недоступен — повторит следующий запуск
            status("ошибка: ${e.message ?: e::class.simpleName}")
            Result.failure()
        }
    }

    companion object {
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<HealthWorker>(PERIOD_HOURS, TimeUnit.HOURS).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun now(context: Context, afterWorkout: Boolean) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<HealthWorker>().setConstraints(network).build())
            if (afterWorkout) {
                val later = OneTimeWorkRequestBuilder<HealthWorker>().setConstraints(network)
                    .setInitialDelay(AFTER_WORKOUT_MIN, TimeUnit.MINUTES).build()
                wm.enqueueUniqueWork(LATER, ExistingWorkPolicy.REPLACE, later)
            }
        }
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
private fun Record.toHealth(): HealthRecord? {
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
