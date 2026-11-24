package train.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.uuid.Uuid

// История упражнения для агента (ADR 0014): последние тренировки целиком, ранние — выборкой по месяцам

/** Лучший подход тренировки против прошлой тренировки с этим упражнением. */
@Serializable
enum class Trend { UP, SAME, DOWN }

/** Упражнение в одной тренировке: все его сделанные подходы, цели и отметки владельца. */
@Serializable
data class HistorySession(
    val workoutId: Uuid,
    val date: LocalDate,
    /** По порядку отметок. */
    val sets: List<DoneSet>,
    /** Целевые подходы строк, где сделаны подходы. */
    val targets: List<TargetSet> = emptyList(),
    val liked: Boolean = false,
    val disliked: Boolean = false,
    /** Заметка владельца к упражнению. */
    val comment: String? = null,
    val bodyweightKg: Double? = null,
    /** Лучший подход по [setScore]: по нему тренд и рекорд. */
    val best: DoneSet? = null,
    val score: Double? = null,
    val trend: Trend? = null,
    /** Единица лучшего подхода и его прошлого сравнения, до обрезки окна истории. */
    val scoreUnit: ScoreUnit? = null,
    val previousScoreUnit: ScoreUnit? = null,
)

/** Месяц за окном последних тренировок: сколько их было и лучшая. [month] — `YYYY-MM`. */
@Serializable
data class HistoryMonth(val month: String, val sessions: Int, val best: HistorySession)

/**
 * История упражнения. [record] — тренировка, где лучший подход впервые достиг максимума;
 * [sinceRecord] — сколько тренировок после неё без нового максимума (длина плато).
 */
@Serializable
data class ExerciseHistory(
    val exerciseId: Uuid,
    val name: String? = null,
    val recordType: RecordType,
    val perSide: Boolean = false,
    val sessions: Int,
    val first: LocalDate? = null,
    val record: HistorySession? = null,
    val sinceRecord: Int = 0,
    /** Новые сначала. */
    val recent: List<HistorySession>,
    /** Новые сначала. */
    val earlier: List<HistoryMonth> = emptyList(),
    /** Записи журнала тренера про это упражнение, новые сначала. */
    val journal: List<JournalEntry> = emptyList(),
    /** Единицы всех оцениваемых подходов, включая скрытые за окном и месячной выборкой. */
    val scoreUnits: Set<ScoreUnit> = emptySet(),
)

/** Телефон не сравнивает секунды с повторами; числовой тренд API/CLI остаётся прежним. */
val HistorySession.comparableTrend: Trend?
    get() = trend.takeIf { scoreUnit != null && scoreUnit == previousScoreUnit }

/** Без метаданных старого сервера однородность полной истории неизвестна. */
val ExerciseHistory.hasComparablePlateau: Boolean
    get() = scoreUnits.size == 1

/** Сколько последних тренировок показывать целиком. */
const val HISTORY_RECENT = 8

/** Изменение меньше этой доли — «так же»: шум оценки, а не прогресс. */
const val TREND_TOLERANCE = 0.005

/** Погрешность Double на самой границе 0,5 % не должна превращаться в рекорд. */
internal fun improvesRecord(score: Double, previous: Double): Boolean =
    score - previous > abs(previous) * TREND_TOLERANCE + maxOf(abs(score), abs(previous), 1.0) * 1e-12

/**
 * Сила подхода одним числом, чтобы сравнивать тренировки с разными весами и повторами.
 * Вес × повторы — оценка разового максимума по Эпли: вес × (1 + повторы / 30). Повторы с долей
 * веса тела — то же от (доп. вес + вес тела × доля); без доли или взвешивания — повторы + доп.
 * вес (кг за повтор). Время — секунды, вес × время — их произведение.
 */
fun setScore(set: DoneSet, bodyweightFactor: Double?, bodyweight: Double?): Double? =
    scoredSet(set, bodyweightFactor, bodyweight)?.value

/** Единицы оценки, чтобы график не выдавал повторы или вес × время за разовый максимум. */
@Serializable
enum class ScoreUnit { ESTIMATED_KG, REPS, REPS_PLUS_KG, SECONDS, KG_SECONDS }

data class ScoredSet(val value: Double, val unit: ScoreUnit)

/** Та же оценка, что у рекорда и тренда, вместе с единицами её оси. */
fun scoredSet(set: DoneSet, bodyweightFactor: Double?, bodyweight: Double?): ScoredSet? = when (set.recordType) {
    RecordType.WEIGHT_REPS -> {
        val w = set.weight
        val r = set.reps
        if (w == null || r == null) null else if (w > 0) ScoredSet(epley(w, r), ScoreUnit.ESTIMATED_KG)
        else ScoredSet(r.toDouble(), ScoreUnit.REPS)
    }
    RecordType.REPS -> set.reps?.let { r ->
        val extra = set.extraWeight ?: 0.0
        if (bodyweightFactor != null && bodyweight != null)
            ScoredSet(epley(extra + bodyweight * bodyweightFactor, r), ScoreUnit.ESTIMATED_KG)
        else ScoredSet(r + extra, if (extra == 0.0) ScoreUnit.REPS else ScoreUnit.REPS_PLUS_KG)
    }
    RecordType.TIME -> set.seconds?.let { ScoredSet(it.toDouble(), ScoreUnit.SECONDS) }
    RecordType.WEIGHT_TIME -> set.seconds?.let { s ->
        set.weight?.takeIf { it > 0 }?.let { ScoredSet(it * s, ScoreUnit.KG_SECONDS) }
            ?: ScoredSet(s.toDouble(), ScoreUnit.SECONDS)
    }
}

private fun epley(load: Double, reps: Int) = load * (1 + reps / 30.0)

/**
 * История упражнений [exerciseIds] по сделанным неудалённым подходам неудалённых тренировок:
 * [recent] последних тренировок целиком — или все с [since], если он задан, — остальные — лучшая за месяц. Вес тела тренировки — по
 * [bodyweightFor], до первого взвешивания — первое взвешивание: иначе оценка подтягиваний
 * прыгала бы между формулами.
 */
fun history(
    exerciseIds: List<Uuid>,
    workouts: List<Workout>,
    sets: List<SetFact>,
    exercises: Map<Uuid, Exercise>,
    measurements: List<Measurement> = emptyList(),
    journal: List<JournalEntry> = emptyList(),
    recent: Int = HISTORY_RECENT,
    zone: TimeZone = TimeZone.UTC,
    since: LocalDate? = null,
): List<ExerciseHistory> {
    val alive = workouts.filter { !it.deleted }.associateBy { it.id }
    val done = sets.filter { it.done && !it.deleted && it.workoutId in alive }
    val firstWeight = measurements.filter { it.type == BODYWEIGHT && !it.deleted }.minByOrNull { it.measuredAt }?.value

    return exerciseIds.distinct().map { exId ->
        val ex = exercises[exId]
        val sessions = done.filter { it.exerciseId == exId }.groupBy { it.workoutId }
            .map { (wid, facts) -> session(alive.getValue(wid), facts, ex, measurements, firstWeight, zone) }
            .sortedByDescending { alive.getValue(it.workoutId).startedAt }
            .let(::withTrends)
        // Рекорд — в единицах последней оценённой тренировки: секунды с кг × с не сравниваются.
        val unit = sessions.firstOrNull { it.score != null }?.scoreUnit
        val record = sessions.filter { it.score != null && it.scoreUnit == unit }.reversed().fold(null as HistorySession?) { best, s ->
            if (best == null || improvesRecord(s.score!!, best.score!!)) s else best
        }
        val full = if (since != null) sessions.takeWhile { it.date >= since } else sessions.take(recent)
        val older = sessions.drop(full.size)
        ExerciseHistory(
            exerciseId = exId,
            name = ex?.nameRu,
            recordType = ex?.recordType ?: sessions.firstOrNull()?.sets?.firstOrNull()?.recordType ?: RecordType.WEIGHT_REPS,
            perSide = ex?.perSide ?: false,
            sessions = sessions.size,
            first = sessions.lastOrNull()?.date,
            record = record,
            sinceRecord = record?.let { r -> sessions.indexOf(r) } ?: 0,
            recent = full,
            earlier = older.groupBy { it.date.toString().take(7) }.map { (month, ss) ->
                HistoryMonth(month, ss.size, ss.maxByOrNull { it.score ?: -1.0 }!!)
            },
            journal = journal.filter { exId in it.exerciseIds && it.deletedAt == null }.sortedByDescending { it.date },
            scoreUnits = sessions.flatMap { s ->
                s.sets.mapNotNull { scoredSet(it, ex?.bodyweightFactor, s.bodyweightKg)?.unit }
            }.toSet(),
        )
    }
}

private fun session(
    w: Workout,
    facts: List<SetFact>,
    ex: Exercise?,
    measurements: List<Measurement>,
    firstWeight: Double?,
    zone: TimeZone,
): HistorySession {
    val itemIds = facts.map { it.itemId }.toSet()
    val items = w.layout.blocks.flatMap { it.items }.filter { it.id in itemIds }
    val ordered = facts.sortedWith(compareBy<SetFact>({ it.doneAt }, { it.setNo })).map { it.toDone(ex?.id) }
    val bw = bodyweightFor(w, measurements) ?: firstWeight
    val scored = ordered.mapNotNull { s -> scoredSet(s, ex?.bodyweightFactor, bw)?.let { s to it } }.maxByOrNull { it.second.value }
    return HistorySession(
        workoutId = w.id,
        date = w.date ?: w.localDate(zone),
        sets = ordered,
        targets = items.flatMap { it.sets },
        liked = items.any { it.liked },
        disliked = items.any { it.disliked },
        comment = items.mapNotNull { it.comment?.trim()?.takeIf(String::isNotEmpty) }.joinToString("\n").takeIf { it.isNotEmpty() },
        bodyweightKg = bw,
        best = scored?.first,
        score = scored?.second?.value,
        scoreUnit = scored?.second?.unit,
    )
}

/** Тренд каждой тренировки против следующей в списке (более ранней). */
private fun withTrends(newestFirst: List<HistorySession>): List<HistorySession> = newestFirst.mapIndexed { i, s ->
    val prev = newestFirst.getOrNull(i + 1)?.score
    val cur = s.score
    s.copy(
        previousScoreUnit = newestFirst.getOrNull(i + 1)?.scoreUnit,
        trend = if (prev == null || cur == null) null else when {
            cur > prev * (1 + TREND_TOLERANCE) -> Trend.UP
            cur < prev * (1 - TREND_TOLERANCE) -> Trend.DOWN
            else -> Trend.SAME
        },
    )
}

/**
 * Упражнения основных блоков тренировок, новые тренировки сначала, в порядке раскладки: что
 * сейчас в программе. Убранные из тренировки не берутся, замены и добавленные — берутся.
 */
fun mainExercises(workouts: List<Workout>, sets: List<SetFact>): List<Uuid> {
    val doneIn = sets.filter { it.done && !it.deleted }.groupBy { it.workoutId }
    return workouts.filter { !it.deleted }.sortedByDescending { it.startedAt }.flatMap { w ->
        val main = w.layout.blocks.filter { it.role == BlockRole.MAIN }
        val mainIds = main.map { it.id }.toSet()
        main.flatMap { b -> b.items.filter { !it.removed }.map { it.exerciseId } } +
            doneIn[w.id].orEmpty().filter { it.blockId in mainIds }.map { it.exerciseId }
    }.distinct()
}
