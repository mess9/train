package train.core

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.offsetAt
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Сон, пульс и шаги с браслета (ADR 0015): телефон шлёт записи Health Connect как есть,
// ночи и дни считает healthDays — на сервере, как остальную аналитику (ADR 0002)

/** Что за запись. Значение [HealthRecord.value] — в единицах типа. */
@Serializable
enum class HealthType {
    /** Шаги за интервал, штук. */
    STEPS,

    /** Сон: интервал и фазы [HealthRecord.stages]. */
    SLEEP,

    /** Пульс: точки [HealthRecord.hrSec] / [HealthRecord.hrBpm]. */
    HEART_RATE,

    /** Пульс покоя от источника, уд/мин. */
    RESTING_HEART_RATE,

    /** Вариабельность пульса, RMSSD, мс. */
    HRV,

    /** Сатурация, %. */
    SPO2,

    /** Частота дыхания, вдохов в минуту. */
    RESPIRATORY_RATE,

    /** Тренировка, записанная источником: [HealthRecord.exerciseType] и [HealthRecord.title]. */
    EXERCISE,
}

@Serializable
enum class SleepStageType {
    AWAKE,
    SLEEPING,
    OUT_OF_BED,
    LIGHT,
    DEEP,
    REM,
    AWAKE_IN_BED,
    UNKNOWN;

    /** Сон без разбивки на фазы ([SLEEPING], [UNKNOWN]) — тоже сон. */
    val asleep get() = this == SLEEPING || this == LIGHT || this == DEEP || this == REM || this == UNKNOWN
}

@Serializable
data class SleepStage(val start: Instant, val end: Instant, val stage: SleepStageType)

/**
 * Запись источника как есть — например, Health Connect на телефоне. [id] — ID записи в
 * источнике: повторная отправка той же записи заменяет её, а не дублирует. [source] — кто
 * записал: пакет приложения, `com.xiaomi.wearable`.
 */
@Serializable
data class HealthRecord(
    val id: String,
    val type: HealthType,
    val source: String,
    val start: Instant,
    /** Конец интервала; у точечных записей (пульс покоя, SpO2) — `null`. */
    val end: Instant? = null,
    /** Смещение пояса на момент записи, секунды: по нему день записи и время отбоя. */
    val zoneOffset: Int? = null,
    /** Шаги — штук, пульс покоя — уд/мин, HRV — мс, SpO2 — %, дыхание — вдохов в минуту. */
    val value: Double? = null,
    /** Фазы сна; у сна без фаз — пусто, весь интервал считается сном. */
    val stages: List<SleepStage>? = null,
    /** Пульс: секунды от [start] и удары в минуту — два списка одной длины. */
    val hrSec: List<Int>? = null,
    val hrBpm: List<Int>? = null,
    /** Тип тренировки по Health Connect (`ExerciseSessionRecord.EXERCISE_TYPE_…`). */
    val exerciseType: Int? = null,
    val title: String? = null,
    /** Когда источник последний раз менял запись. */
    val modifiedAt: Instant? = null,
)

/** Пачка с телефона: новые и изменённые записи, ID удалённых в источнике. */
@Serializable
data class HealthUpload(val records: List<HealthRecord> = emptyList(), val deleted: List<String> = emptyList())

/** [excluded] — записи, попавшие в [HealthExclusion], сервер не сохранил. */
@Serializable
data class HealthUploaded(val saved: Int, val deleted: Int, val excluded: Int)

/**
 * Дни, данные которых не владельца: например, браслет носил другой человек. Записи этих дней
 * удаляются и больше не принимаются. [source] — только от этого источника; `null` — от всех.
 */
@Serializable
data class HealthExclusion(
    val id: Uuid,
    val from: LocalDate,
    val to: LocalDate,
    val source: String? = null,
    val note: String? = null,
)

@Serializable
data class HealthExclusionCreate(val from: LocalDate, val to: LocalDate, val source: String? = null, val note: String? = null)

/** [removed] — сколько уже сохранённых записей удалено. */
@Serializable
data class HealthExcluded(val exclusion: HealthExclusion, val removed: Int)

/**
 * Ночь, закончившаяся утром дня: основной сон — самый длинный кусок, где перерывы меньше
 * [SLEEP_GAP]. Фазы и пробуждения — внутри него; сон вне — [napMin].
 */
@Serializable
data class SleepNight(
    val bedAt: Instant,
    val wakeAt: Instant,
    /** Смещение пояса для времени отбоя и подъёма, секунды. */
    val zoneOffset: Int,
    val asleepMin: Int,
    /** Фазы — `null`, если источник их не записал. */
    val deepMin: Int? = null,
    val remMin: Int? = null,
    val lightMin: Int? = null,
    val awakeMin: Int = 0,
    /** Сон вне основного куска: дневной, вечерний до отбоя. */
    val napMin: Int = 0,
    val source: String,
)

/**
 * День: ночь, закончившаяся этим утром, шаги, пульс. Пульс ночью — по точкам пульса во время
 * основного сна ([NIGHT_HR_MIN_POINTS] и больше); пульс покоя и HRV — только если их записал
 * источник: сами не выводим.
 */
@Serializable
data class HealthDay(
    val date: LocalDate,
    val sleep: SleepNight? = null,
    /** Шаги: у каждого источника свои, берём больший — телефон в кармане не всегда. */
    val steps: Int? = null,
    val nightHrMin: Int? = null,
    val nightHrMedian: Int? = null,
    val restingHr: Int? = null,
    val hrv: Double? = null,
    /** SpO2 во время основного сна; сна нет — за день. */
    val spo2: Double? = null,
    val spo2Min: Double? = null,
    val respiratoryRate: Double? = null,
    /** День в [HealthExclusion], и других источников у него нет: данных нет намеренно. */
    val excluded: Boolean = false,
)

/** Дни периода, старые сначала. */
@Serializable
data class HealthReport(
    val from: LocalDate,
    val to: LocalDate,
    val days: List<HealthDay>,
    val exclusions: List<HealthExclusion> = emptyList(),
    /** Источники записей периода. */
    val sources: List<String> = emptyList(),
)

/** Перерыв в сне короче — тот же сон. */
val SLEEP_GAP: Duration = 90.minutes

/** Меньше точек пульса за ночь — пульс ночью не считаем: случайные замеры, не ночь. */
const val NIGHT_HR_MIN_POINTS = 10

/** Ночь дня D — сон, начавшийся с 18:00 D−1 до 18:00 D местного времени. */
private val NIGHT_SHIFT = 6.hours

private fun offsetOf(seconds: Int?, at: Instant, zone: TimeZone): UtcOffset =
    seconds?.let { UtcOffset(seconds = it) } ?: zone.offsetAt(at)

fun HealthRecord.offset(zone: TimeZone): UtcOffset = offsetOf(zoneOffset, start, zone)

fun HealthRecord.localStart(zone: TimeZone): LocalDateTime = start.toLocalDateTime(offset(zone).asTimeZone())

/** День записи — по поясу, в котором она сделана. */
fun HealthRecord.date(zone: TimeZone): LocalDate = localStart(zone).date

fun HealthExclusion.covers(r: HealthRecord, zone: TimeZone): Boolean =
    (source == null || source == r.source) && r.date(zone).let { it >= from && it <= to }

private fun Instant.local(offsetSec: Int): LocalDateTime = toLocalDateTime(UtcOffset(seconds = offsetSec).asTimeZone())

/** Ночь, к которой относится момент: местная дата момента + 6 ч. */
private fun nightOf(t: Instant, offsetSec: Int): LocalDate = (t + NIGHT_SHIFT).local(offsetSec).date

private class Span(val start: Instant, var end: Instant)

private fun union(spans: List<Pair<Instant, Instant>>, gap: Duration = Duration.ZERO): List<Span> {
    val out = mutableListOf<Span>()
    for ((a, b) in spans.sortedBy { it.first }) {
        val last = out.lastOrNull()
        if (last != null && a - last.end <= gap) {
            if (b > last.end) last.end = b
        } else {
            out += Span(a, b)
        }
    }
    return out
}

private fun minutes(spans: List<Span>): Int = spans.sumOf { (it.end - it.start).inWholeSeconds }.let { (it / 60).toInt() }

/** Пересечение интервалов с окном [from, to). */
private fun clip(spans: List<Pair<Instant, Instant>>, from: Instant, to: Instant) =
    spans.mapNotNull { (a, b) -> maxOf(a, from).let { s -> minOf(b, to).takeIf { it > s }?.let { s to it } } }

/** Ночи по дате подъёма: у каждой ночи один источник — тот, у кого сна больше. */
fun sleepNights(records: List<HealthRecord>, zone: TimeZone): Map<LocalDate, SleepNight> {
    // (ночь, источник) → фазы
    val byNight = mutableMapOf<Pair<LocalDate, String>, MutableList<Pair<SleepStage, Int>>>()
    for (r in records) {
        if (r.type != HealthType.SLEEP) continue
        val end = r.end ?: continue
        val off = r.offset(zone).totalSeconds
        val stages = r.stages?.takeIf { it.isNotEmpty() } ?: listOf(SleepStage(r.start, end, SleepStageType.SLEEPING))
        for (s in stages) {
            if (s.end <= s.start) continue
            byNight.getOrPut(nightOf(s.start, off) to r.source) { mutableListOf() } += s to off
        }
    }
    val best = byNight.entries.groupBy { it.key.first }.mapValues { (_, list) ->
        list.maxBy { e -> minutes(union(e.value.filter { it.first.stage.asleep }.map { it.first.start to it.first.end })) }
    }
    return best.mapNotNull { (date, e) ->
        val stages = e.value.map { it.first }
        val off = e.value.first().second
        val asleep = stages.filter { it.stage.asleep }.map { it.start to it.end }
        val blocks = union(asleep, SLEEP_GAP)
        val main = blocks.maxByOrNull { it.end - it.start } ?: return@mapNotNull null
        fun mins(vararg types: SleepStageType) =
            minutes(union(clip(stages.filter { it.stage in types }.map { it.start to it.end }, main.start, main.end)))
        val staged = stages.any { it.stage == SleepStageType.DEEP || it.stage == SleepStageType.REM || it.stage == SleepStageType.LIGHT }
        val inMain = minutes(union(clip(asleep, main.start, main.end)))
        date to SleepNight(
            bedAt = main.start,
            wakeAt = main.end,
            zoneOffset = off,
            asleepMin = inMain,
            deepMin = if (staged) mins(SleepStageType.DEEP) else null,
            remMin = if (staged) mins(SleepStageType.REM) else null,
            lightMin = if (staged) mins(SleepStageType.LIGHT) else null,
            awakeMin = mins(SleepStageType.AWAKE, SleepStageType.AWAKE_IN_BED, SleepStageType.OUT_OF_BED),
            napMin = minutes(union(asleep)) - inMain,
            source = e.key.second,
        )
    }.toMap()
}

private fun median(xs: List<Int>): Int = xs.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

private fun round1(d: Double) = kotlin.math.round(d * 10) / 10

/**
 * Дни [from]..[to] подряд, пустой день — тоже строка. Записи из [exclusions] не учитываются,
 * их дни помечены [HealthDay.excluded].
 */
fun healthDays(
    records: List<HealthRecord>,
    from: LocalDate,
    to: LocalDate,
    zone: TimeZone,
    exclusions: List<HealthExclusion> = emptyList(),
): List<HealthDay> {
    val rs = records.filter { r -> exclusions.none { it.covers(r, zone) } }
    val nights = sleepNights(rs, zone)
    val byDate = rs.groupBy { it.date(zone) }
    // Точки пульса всех записей — по времени
    val hr = rs.filter { it.type == HealthType.HEART_RATE }.flatMap { r ->
        val sec = r.hrSec.orEmpty()
        val bpm = r.hrBpm.orEmpty()
        sec.indices.filter { it < bpm.size }.map { r.start + sec[it].seconds to bpm[it] }
    }.sortedBy { it.first }
    val hrTimes = hr.map { it.first }
    fun hrIn(a: Instant, b: Instant): List<Int> {
        val i = hrTimes.binarySearch(a).let { if (it < 0) -it - 1 else it }
        return buildList { var k = i; while (k < hr.size && hr[k].first <= b) add(hr[k++].second) }
    }
    val spo2 = rs.filter { it.type == HealthType.SPO2 && it.value != null }

    return generateSequence(from) { it.plus(DatePeriod(days = 1)) }.takeWhile { it <= to }.map { d ->
        val day = byDate[d].orEmpty()
        val night = nights[d]
        fun avg(t: HealthType) = day.filter { it.type == t }.mapNotNull { it.value }.takeIf { it.isNotEmpty() }?.average()
        val nightHr = night?.let { hrIn(it.bedAt, it.wakeAt) }?.takeIf { it.size >= NIGHT_HR_MIN_POINTS }
        val sp = (night?.let { n -> spo2.filter { it.start >= n.bedAt && it.start <= n.wakeAt } }?.takeIf { it.isNotEmpty() }
            ?: day.filter { it.type == HealthType.SPO2 && it.value != null }).mapNotNull { it.value }
        HealthDay(
            date = d,
            sleep = night,
            steps = day.filter { it.type == HealthType.STEPS }.groupBy { it.source }
                .maxOfOrNull { (_, list) -> list.sumOf { it.value ?: 0.0 } }?.toInt(),
            nightHrMin = nightHr?.min(),
            nightHrMedian = nightHr?.let(::median),
            restingHr = avg(HealthType.RESTING_HEART_RATE)?.let { kotlin.math.round(it).toInt() },
            hrv = avg(HealthType.HRV)?.let(::round1),
            spo2 = sp.takeIf { it.isNotEmpty() }?.average()?.let(::round1),
            spo2Min = sp.minOrNull(),
            respiratoryRate = avg(HealthType.RESPIRATORY_RATE)?.let(::round1),
        ).let { h ->
            // Исключён только источник, а другие что-то записали — день обычный
            if (h == HealthDay(d) && exclusions.any { d >= it.from && d <= it.to }) h.copy(excluded = true) else h
        }
    }.toList()
}

// Текст для агента (ADR 0014)

private fun hm(min: Int) = "${min / 60}:${(min % 60).toString().padStart(2, '0')}"

private fun clock(t: Instant, off: Int) = t.local(off).let { "${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}" }

/** Строка дня: `15.11 вс сон 8:29 02:46–11:28 глуб 0:20 REM 1:16 лёгк 6:53 проб 0:13 · пульс ночью 49/54 · SpO2 94 · шаги 1062`. */
fun HealthDay.toText(): String {
    val parts = mutableListOf<String>()
    sleep?.let { s ->
        parts += buildString {
            append("сон ${hm(s.asleepMin)} ${clock(s.bedAt, s.zoneOffset)}–${clock(s.wakeAt, s.zoneOffset)}")
            if (s.deepMin != null) append(" глуб ${hm(s.deepMin)} REM ${hm(s.remMin ?: 0)} лёгк ${hm(s.lightMin ?: 0)}")
            if (s.awakeMin > 0) append(" проб ${hm(s.awakeMin)}")
            if (s.napMin > 0) append(" +дрёма ${hm(s.napMin)}")
        }
    }
    if (nightHrMin != null) parts += "пульс ночью $nightHrMin/$nightHrMedian"
    restingHr?.let { parts += "покоя $it" }
    hrv?.let { parts += "HRV ${num(it)}" }
    spo2?.let { parts += "SpO2 ${num(it)}" }
    respiratoryRate?.let { parts += "дыхание ${num(it)}" }
    steps?.let { parts += "шаги $it" }
    val body = when {
        excluded -> "исключён"
        parts.isEmpty() -> "нет данных"
        else -> parts.joinToString(" · ")
    }
    return "${date.dm()} ${date.weekday()} $body"
}

/** Средние за дни с данными: сон, отбой, пульс. Для сравнения «последняя неделя против прошлых». */
@Serializable
data class HealthSummary(
    val nights: Int,
    val sleepAvgMin: Int? = null,
    val sleepMinMin: Int? = null,
    /** Ночей короче 6 часов. */
    val shortNights: Int = 0,
    /** Отбой: раньше всех и позже всех, местное время. */
    val bedEarliest: String? = null,
    val bedLatest: String? = null,
    /** Разброс отбоя — стандартное отклонение, минут. */
    val bedSpreadMin: Int? = null,
    val deepAvgMin: Int? = null,
    val nightHrMedian: Int? = null,
    val restingHr: Int? = null,
    val hrv: Double? = null,
    val stepsMedian: Int? = null,
)

/** Короткий сон — меньше 6 часов. */
const val SHORT_SLEEP_MIN = 6 * 60

fun healthSummary(days: List<HealthDay>): HealthSummary {
    val ds = days.filter { !it.excluded }
    val nights = ds.mapNotNull { it.sleep }
    // Отбой в минутах от полуночи: вечер — отрицательные, чтобы 23:30 и 01:00 были рядом
    val beds = nights.map { n -> n.bedAt.local(n.zoneOffset).let { it.hour * 60 + it.minute }.let { if (it >= 18 * 60) it - 1440 else it } }
    fun fmt(m: Int) = ((m + 1440) % 1440).let { "${(it / 60).toString().padStart(2, '0')}:${(it % 60).toString().padStart(2, '0')}" }
    fun avgInt(xs: List<Int>) = xs.takeIf { it.isNotEmpty() }?.average()?.let { kotlin.math.round(it).toInt() }
    return HealthSummary(
        nights = nights.size,
        sleepAvgMin = avgInt(nights.map { it.asleepMin }),
        sleepMinMin = nights.minOfOrNull { it.asleepMin },
        shortNights = nights.count { it.asleepMin < SHORT_SLEEP_MIN },
        bedEarliest = beds.minOrNull()?.let(::fmt),
        bedLatest = beds.maxOrNull()?.let(::fmt),
        bedSpreadMin = beds.takeIf { it.size >= 2 }?.let { b ->
            val m = b.average()
            kotlin.math.round(kotlin.math.sqrt(b.sumOf { (it - m) * (it - m) } / b.size)).toInt()
        },
        deepAvgMin = avgInt(nights.mapNotNull { it.deepMin }),
        nightHrMedian = ds.mapNotNull { it.nightHrMedian }.takeIf { it.isNotEmpty() }?.let(::median),
        restingHr = avgInt(ds.mapNotNull { it.restingHr }),
        hrv = ds.mapNotNull { it.hrv }.takeIf { it.isNotEmpty() }?.average()?.let(::round1),
        stepsMedian = ds.mapNotNull { it.steps }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.let(::median),
    )
}

fun HealthSummary.toText(): String {
    if (nights == 0 && stepsMedian == null) return "нет данных"
    return listOfNotNull(
        sleepAvgMin?.let { "сон ср ${hm(it)} (мин ${hm(sleepMinMin ?: it)}), короче 6 ч: $shortNights из $nights" },
        bedEarliest?.let { "отбой $it–$bedLatest" + (bedSpreadMin?.let { s -> ", разброс ±${hm(s)}" } ?: "") },
        deepAvgMin?.let { "глуб ср ${hm(it)}" },
        nightHrMedian?.let { "пульс ночью мед $it" },
        restingHr?.let { "покоя ср $it" },
        hrv?.let { "HRV ср ${num(it)}" },
        stepsMedian?.let { "шаги мед $it" },
    ).joinToString(" · ")
}

/**
 * Отчёт текстом: шапка, итог последних [recent] дней против остальных дней периода, [lines]
 * дней построчно, новые сначала. Источник фаз сна меняется вместе с браслетом — глубокий сон
 * разных браслетов не сравнивать.
 */
fun HealthReport.toText(recent: Int = 7, lines: Int = days.size): String {
    val withData = days.filter { it.sleep != null || it.steps != null || it.excluded }
    if (withData.isEmpty()) return "нет данных о сне и пульсе за ${from.dm()}–${to.dm()}"
    val last = days.takeLast(recent)
    val before = days.dropLast(recent)
    return buildString {
        appendLine("${from.dm()}–${to.dm()} · источники: ${sources.joinToString(", ").ifEmpty { "—" }}")
        exclusions.forEach { appendLine("исключено ${it.from.dm()}–${it.to.dm()}${it.source?.let { s -> " ($s)" } ?: ""}${it.note?.let { n -> ": $n" } ?: ""}") }
        appendLine("последние ${last.size} дн.: ${healthSummary(last).toText()}")
        if (before.isNotEmpty()) appendLine("${before.first().date.dm()}–${before.last().date.dm()}: ${healthSummary(before).toText()}")
        days.asReversed().take(lines).forEach { appendLine(it.toText()) }
    }.trimEnd()
}
