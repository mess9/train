package train.core

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Неделя с понедельника для агента (ADR 0014): сколько тренировок и минут, рабочие подходы и их
 * оценки, добавленные и пропущенные, объём, вес тела, нагрузка по группам мышц — признаки
 * перегруза и недогруза. Неделя без тренировок тоже есть: пропуск — тоже факт.
 */
@Serializable
data class WeekSummary(
    val from: LocalDate,
    val to: LocalDate,
    val workouts: Int,
    /** Минуты каждой тренировки по порядку. */
    val minutes: List<Int> = emptyList(),
    val workSets: Int = 0,
    /** Сверх плана, во всех блоках. */
    val added: Int = 0,
    /** Плановые без отметки, во всех блоках. */
    val skipped: Int = 0,
    /** Оценки рабочих подходов. */
    val easy: Int = 0,
    val normal: Int = 0,
    val hard: Int = 0,
    val volumeKg: Double = 0.0,
    /** Последнее взвешивание недели. */
    val bodyweightKg: Double? = null,
    /** Как в [Stats.groups]: основная мышца — подход, вспомогательная — половина. */
    val groups: List<GroupLoad> = emptyList(),
)

/** Понедельник недели дня. */
fun LocalDate.monday(): LocalDate = minus(DatePeriod(days = dayOfWeek.ordinal))

/** Недели с понедельника [from] по неделю [to], старые сначала. */
fun weeks(
    from: LocalDate,
    to: LocalDate,
    workouts: List<Workout>,
    sets: List<SetFact>,
    exercises: Map<Uuid, Exercise>,
    measurements: List<Measurement> = emptyList(),
    zone: TimeZone = TimeZone.UTC,
): List<WeekSummary> {
    val alive = workouts.filter { !it.deleted }
    val day = { w: Workout -> w.date ?: w.localDate(zone) }
    val weights = measurements.filter { it.type == BODYWEIGHT && !it.deleted }
    return generateSequence(from.monday()) { it.plus(DatePeriod(days = 7)) }.takeWhile { it <= to }.map { start ->
        val end = start.plus(DatePeriod(days = 6))
        val ws = alive.filter { day(it) in start..end }.sortedBy { it.startedAt }
        val s = stats(start, end, ws, sets, exercises, measurements, zone)
        val comparisons = ws.map { compare(it, sets, exercises) }
        val mainBlocks = ws.flatMap { w -> w.layout.blocks.filter { it.role == BlockRole.MAIN }.map { it.id } }.toSet()
        val work = sets.filter { it.done && !it.deleted && it.blockId in mainBlocks && ws.any { w -> w.id == it.workoutId } }
        WeekSummary(
            from = start,
            to = end,
            workouts = ws.size,
            minutes = comparisons.map { ((it.totals.durationSec ?: 0) / 60).toInt() },
            workSets = work.size,
            added = comparisons.sumOf { it.totals.setsAdded },
            skipped = comparisons.sumOf { it.totals.setsSkipped },
            easy = work.count { it.rating == Rating.EASY },
            normal = work.count { it.rating == Rating.NORMAL },
            hard = work.count { it.rating == Rating.HARD },
            volumeKg = s.volumeKg,
            bodyweightKg = weights.filter { it.measuredAt.toLocalDateTime(zone).date in start..end }.maxByOrNull { it.measuredAt }?.value,
            groups = s.groups,
        )
    }.toList()
}
