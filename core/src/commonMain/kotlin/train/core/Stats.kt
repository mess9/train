package train.core

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

// Сводка за период для раздела «Замеры»: карта мышц, роза по частям тела, часы тренировок

/**
 * Участок тела на карте мышц. Мышцы датасета (primary/secondary) сводятся к ним через [regionOf];
 * [group] — луч «розы ветров».
 */
@Serializable
enum class BodyRegion(val group: BodyGroup) {
    CHEST(BodyGroup.CHEST),
    SHOULDERS(BodyGroup.SHOULDERS),
    TRAPS(BodyGroup.BACK),
    UPPER_BACK(BodyGroup.BACK),
    LOWER_BACK(BodyGroup.BACK),
    BICEPS(BodyGroup.ARMS),
    TRICEPS(BodyGroup.ARMS),
    FOREARMS(BodyGroup.ARMS),
    ABS(BodyGroup.CORE),
    OBLIQUES(BodyGroup.CORE),
    GLUTES(BodyGroup.GLUTES),
    QUADS(BodyGroup.LEGS),
    HAMSTRINGS(BodyGroup.LEGS),
    ADDUCTORS(BodyGroup.LEGS),
    CALVES(BodyGroup.LEGS),
}

/** Лучи «розы ветров» — по часовой стрелке от верха. */
@Serializable
enum class BodyGroup { CHEST, SHOULDERS, ARMS, CORE, LEGS, GLUTES, BACK }

/**
 * Участок тела мышцы из датасета: `target` и `secondary_muscles` (английские названия).
 * `null` — не на карте: сердечно-сосудистая система, незнакомое название.
 */
fun regionOf(muscle: String): BodyRegion? = REGIONS[muscle.trim().lowercase()]

private val REGIONS: Map<String, BodyRegion> = buildMap {
    fun put(r: BodyRegion, vararg names: String) = names.forEach { put(it, r) }
    put(BodyRegion.CHEST, "pectorals", "chest", "upper chest")
    put(BodyRegion.SHOULDERS, "delts", "deltoids", "shoulders", "rear deltoids", "rotator cuff")
    put(BodyRegion.TRAPS, "traps", "trapezius", "levator scapulae", "neck", "sternocleidomastoid")
    put(BodyRegion.UPPER_BACK, "upper back", "lats", "latissimus dorsi", "rhomboids", "back")
    put(BodyRegion.LOWER_BACK, "lower back", "spine")
    put(BodyRegion.BICEPS, "biceps", "brachialis")
    put(BodyRegion.TRICEPS, "triceps")
    put(BodyRegion.FOREARMS, "forearms", "wrists", "wrist flexors", "wrist extensors", "hands", "grip muscles")
    put(BodyRegion.ABS, "abs", "abdominals", "lower abs", "core")
    put(BodyRegion.OBLIQUES, "obliques", "serratus anterior")
    put(BodyRegion.GLUTES, "glutes", "abductors")
    put(BodyRegion.QUADS, "quads", "quadriceps", "hip flexors")
    put(BodyRegion.HAMSTRINGS, "hamstrings")
    put(BodyRegion.ADDUCTORS, "adductors", "inner thighs", "groin")
    put(BodyRegion.CALVES, "calves", "soleus", "shins", "ankles", "ankle stabilizers", "feet")
}

/** Вклад подхода в мышцу: основная — целый подход, вспомогательная — половина. */
const val SECONDARY_SHARE = 0.5

/** Нагрузка участка тела: подходы с весами [SECONDARY_SHARE] для вспомогательных мышц. */
@Serializable
data class RegionLoad(val region: BodyRegion, val sets: Double)

/**
 * Луч розы: подход входит в группу один раз — целым, если в ней основная мышца, иначе
 * [SECONDARY_SHARE]. Не сумма участков: у ног их четыре, и выпад иначе давал бы ногам 2 подхода.
 */
@Serializable
data class GroupLoad(val group: BodyGroup, val sets: Double)

/** Мышца как в датасете: сколько подходов она была основной и вспомогательной. Для агента. */
@Serializable
data class MuscleLoad(val muscle: String, val primary: Int, val secondary: Int)

/** День с тренировками. [minutes] — от старта до финиша (у незавершённой — до последней отметки). */
@Serializable
data class DayLoad(val date: LocalDate, val workouts: Int, val minutes: Int, val sets: Int)

/** Сводка за `from..to` по дню тренировки ([Workout.date]). `GET /api/stats`. */
@Serializable
data class Stats(
    val from: LocalDate,
    val to: LocalDate,
    val workouts: Int,
    val minutes: Int,
    val sets: Int,
    val volumeKg: Double,
    /** Все участки, и с нулём: карта красит всё тело. */
    val regions: List<RegionLoad>,
    val muscles: List<MuscleLoad>,
    /** Только дни с тренировками, по порядку. */
    val days: List<DayLoad>,
    /** Все группы, и с нулём. */
    val groups: List<GroupLoad> = emptyList(),
) {
    fun load(region: BodyRegion): Double = regions.firstOrNull { it.region == region }?.sets ?: 0.0

    fun load(group: BodyGroup): Double = groups.firstOrNull { it.group == group }?.sets ?: 0.0
}

/**
 * Сводка за `from..to` включительно. Считаются сделанные и не удалённые подходы неудалённых
 * тренировок; мышцы — из справочника по упражнению подхода, объём — как в [compare] с весом
 * тела [bodyweightFor].
 */
fun stats(
    from: LocalDate,
    to: LocalDate,
    workouts: List<Workout>,
    sets: List<SetFact>,
    exercises: Map<Uuid, Exercise>,
    measurements: List<Measurement> = emptyList(),
    zone: TimeZone = TimeZone.UTC,
): Stats {
    val inRange = workouts.filter { !it.deleted }.associateWith { it.date ?: it.localDate(zone) }.filterValues { it in from..to }
    val ids = inRange.keys.map { it.id }.toSet()
    val done = sets.filter { it.workoutId in ids && it.done && !it.deleted }
    val byWorkout = done.groupBy { it.workoutId }

    val regions = BodyRegion.entries.associateWith { 0.0 }.toMutableMap()
    val groups = BodyGroup.entries.associateWith { 0.0 }.toMutableMap()
    val primary = mutableMapOf<String, Int>()
    val secondary = mutableMapOf<String, Int>()
    for (s in done) {
        val ex = exercises[s.exerciseId] ?: continue
        // Одна мышца, записанная и основной, и вспомогательной, или два названия одного участка — участок один раз
        val main = ex.musclesPrimary.mapNotNull(::regionOf).toSet()
        // Основная не на карте (кардио: бег, скакалка) — вспомогательные ноги не красим, иначе разминка забивает карту
        val extra = if (main.isEmpty()) emptySet() else ex.musclesSecondary.mapNotNull(::regionOf).toSet() - main
        main.forEach { regions[it] = regions.getValue(it) + 1.0 }
        extra.forEach { regions[it] = regions.getValue(it) + SECONDARY_SHARE }
        val mainGroups = main.map { it.group }.toSet()
        mainGroups.forEach { groups[it] = groups.getValue(it) + 1.0 }
        (extra.map { it.group }.toSet() - mainGroups).forEach { groups[it] = groups.getValue(it) + SECONDARY_SHARE }
        ex.musclesPrimary.distinct().forEach { primary[it] = (primary[it] ?: 0) + 1 }
        ex.musclesSecondary.distinct().forEach { secondary[it] = (secondary[it] ?: 0) + 1 }
    }

    fun minutes(w: Workout): Int {
        val end = w.finishedAt ?: byWorkout[w.id].orEmpty().mapNotNull { it.doneAt }.maxOrNull() ?: return 0
        return (end - w.startedAt).inWholeMinutes.toInt().coerceAtLeast(0)
    }

    val days = inRange.entries.groupBy({ it.value }, { it.key }).map { (date, ws) ->
        DayLoad(date, ws.size, ws.sumOf(::minutes), ws.sumOf { byWorkout[it.id].orEmpty().size })
    }.sortedBy { it.date }

    val volume = inRange.keys.sumOf { w ->
        val bw = bodyweightFor(w, measurements)
        byWorkout[w.id].orEmpty().filter { it.recordType.usesReps }.sumOf { it.volume(exercises[it.exerciseId], bw) ?: 0.0 }
    }

    return Stats(
        from = from,
        to = to,
        workouts = inRange.size,
        minutes = days.sumOf { it.minutes },
        sets = done.size,
        volumeKg = volume,
        regions = BodyRegion.entries.map { RegionLoad(it, regions.getValue(it)) },
        muscles = (primary.keys + secondary.keys).map { MuscleLoad(it, primary[it] ?: 0, secondary[it] ?: 0) }
            .sortedWith(compareByDescending<MuscleLoad> { it.primary + it.secondary * SECONDARY_SHARE }.thenBy { it.muscle }),
        days = days,
        groups = BodyGroup.entries.map { GroupLoad(it, groups.getValue(it)) },
    )
}

/** Период сводки. Столбики гистограммы — [buckets]. */
@Serializable
enum class StatsPeriod { WEEK, MONTH, QUARTER, YEAR }

/** Столбик гистограммы: дни `from..to` включительно. */
data class Bucket(val from: LocalDate, val to: LocalDate) {
    operator fun contains(d: LocalDate) = d in from..to
}

/**
 * Столбики периода, последний — с [today]: неделя и месяц — по дням (7 и 30), три месяца — по
 * неделям с понедельника (13), год — по календарным месяцам (12). Начало периода — `first().from`.
 */
fun StatsPeriod.buckets(today: LocalDate): List<Bucket> = when (this) {
    StatsPeriod.WEEK -> days(today, 7)
    StatsPeriod.MONTH -> days(today, 30)
    StatsPeriod.QUARTER -> {
        val monday = today.minus(DatePeriod(days = today.dayOfWeek.ordinal - DayOfWeek.MONDAY.ordinal))
        (12 downTo 0).map { k -> monday.minus(DatePeriod(days = 7 * k)).let { Bucket(it, it.plus(DatePeriod(days = 6))) } }
    }
    StatsPeriod.YEAR -> {
        val first = LocalDate(today.year, today.month, 1)
        (11 downTo 0).map { k ->
            val start = first.minus(DatePeriod(months = k))
            Bucket(start, start.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1)))
        }
    }
}

private fun days(today: LocalDate, n: Int) = (n - 1 downTo 0).map { today.minus(DatePeriod(days = it)).let { d -> Bucket(d, d) } }

/** Минуты тренировок в каждом столбике. */
fun minutesBy(buckets: List<Bucket>, days: List<DayLoad>): List<Int> =
    buckets.map { b -> days.filter { it.date in b }.sumOf { it.minutes } }
