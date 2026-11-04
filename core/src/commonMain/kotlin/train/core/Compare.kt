package train.core

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
enum class SetStatus {
    /** Плановый подход выполнен. */
    DONE,
    /** Плановый подход без отметки. */
    SKIPPED,
    /** Подход сверх плана. */
    ADDED,
}

/** Сделанный подход в коротком виде для агента. */
@Serializable
data class DoneSet(
    val setNo: Int,
    val recordType: RecordType,
    val perSide: Boolean = false,
    val weight: Double? = null,
    val reps: Int? = null,
    val extraWeight: Double? = null,
    val seconds: Int? = null,
    val rating: Rating? = null,
    val note: String? = null,
    val doneAt: Instant? = null,
    /** Только если упражнение подхода не то, что сейчас в строке, — подход до замены. */
    val exerciseId: Uuid? = null,
)

@Serializable
data class SetComparison(val setNo: Int, val status: SetStatus, val target: TargetSet? = null, val fact: DoneSet? = null)

@Serializable
data class ItemComparison(
    val itemId: Uuid,
    val exerciseId: Uuid,
    val name: String? = null,
    val replacedFrom: Uuid? = null,
    val added: Boolean = false,
    val removed: Boolean = false,
    /** Сердечко владельца: упражнение очень понравилось. */
    val liked: Boolean = false,
    /** Заметка владельца к упражнению. */
    val comment: String? = null,
    /** Заметка плана к упражнению. */
    val note: String? = null,
    val sets: List<SetComparison>,
)

@Serializable
data class BlockComparison(val blockId: Uuid, val role: BlockRole, val title: String? = null, val items: List<ItemComparison>)

@Serializable
data class Totals(
    val durationSec: Long? = null,
    val setsPlanned: Int,
    val setsDone: Int,
    val setsSkipped: Int,
    val setsAdded: Int,
    /**
     * Сделанные подходы в основных блоках, без разминки и заминки. Мера нагрузки, которую можно
     * сравнивать между упражнениями: подход есть подход, хоть с гантелями, хоть скакалка.
     */
    val workSets: Int,
    /** Объём сделанных подходов, где он известен, кг; с долей собственного веса. */
    val volumeKg: Double,
    /** Подходы на повторы, у которых объём неизвестен: в `volumeKg` их нет. */
    val volumeUnknownSets: Int,
    /** Сумма подходов на время. */
    val timeUnderLoadSec: Int,
    /** Темп = общее время / общий объём. */
    val paceSecPerKg: Double? = null,
)

/** Прошлая тренировка с этим упражнением. */
@Serializable
data class PreviousResult(val exerciseId: Uuid, val name: String? = null, val workoutId: Uuid, val sets: List<DoneSet>)

@Serializable
data class Comparison(
    val workoutId: Uuid,
    val planId: Uuid? = null,
    val planVersion: Int? = null,
    val startedAt: Instant,
    /** День тренировки по часам телефона. */
    val date: LocalDate? = null,
    val finishedAt: Instant? = null,
    val comment: String? = null,
    /** Собственный вес, с которым посчитан объём ([bodyweightFor]). */
    val bodyweightKg: Double? = null,
    /** Рост из профиля, см. В объём не входит. */
    val heightCm: Double? = null,
    /** Замеры, привязанные к тренировке. */
    val measurements: List<Measurement> = emptyList(),
    val totals: Totals,
    val blocks: List<BlockComparison>,
    val previous: List<PreviousResult>,
    /** Сделанные подходы, которым не нашлось места в раскладке. Пусто, если всё в порядке. */
    val unplaced: List<DoneSet> = emptyList(),
)

/**
 * План против факта: по каждому целевому подходу цель / факт / оценка / отметка, добавленные,
 * пропущенные и заменённые подходы, итоги и прошлые результаты этих упражнений. План — снимок
 * в раскладке тренировки, а не текущая версия.
 *
 * @param previous прошлые факты этих упражнений из других тренировок
 * @param bodyweight вес тела для объёма — [bodyweightFor]
 * @param measurements замеры, привязанные к тренировке
 */
fun compare(
    workout: Workout,
    facts: List<SetFact>,
    exercises: Map<Uuid, Exercise>,
    previous: List<SetFact> = emptyList(),
    bodyweight: Double? = null,
    heightCm: Double? = null,
    measurements: List<Measurement> = emptyList(),
): Comparison {
    val done = facts.filter { it.workoutId == workout.id && it.done && !it.deleted }
    val doneByItem = done.groupBy { it.itemId }
    val name = { id: Uuid -> exercises[id]?.nameRu }

    val blocks = workout.layout.blocks.map { block ->
        BlockComparison(block.id, block.role, block.title, block.items.map { item ->
            val bySetNo = doneByItem[item.id].orEmpty().associateBy { it.setNo }
            val count = maxOf(item.sets.size, bySetNo.keys.maxOrNull() ?: 0)
            val sets = (1..count).mapNotNull { n ->
                val target = item.sets.getOrNull(n - 1)
                val fact = bySetNo[n]?.toDone(item.exerciseId)
                when {
                    target != null && fact != null -> SetComparison(n, SetStatus.DONE, target, fact)
                    target != null -> SetComparison(n, SetStatus.SKIPPED, target)
                    fact != null -> SetComparison(n, SetStatus.ADDED, fact = fact)
                    else -> null
                }
            }
            ItemComparison(
                item.id, item.exerciseId, name(item.exerciseId), item.replacedFrom, item.added, item.removed,
                item.liked, item.comment, item.note, sets,
            )
        })
    }

    val placed = workout.layout.blocks.flatMap { it.items }.map { it.id }.toSet()
    val all = blocks.flatMap { it.items }.flatMap { it.sets }
    val end = workout.finishedAt ?: done.mapNotNull { it.doneAt }.maxOrNull()
    val duration = end?.let { (it - workout.startedAt).inWholeSeconds }
    val volumes = done.filter { it.recordType.usesReps }.map { it.volume(exercises[it.exerciseId], bodyweight) }
    val volume = volumes.sumOf { it ?: 0.0 }
    val mainBlocks = workout.layout.blocks.filter { it.role == BlockRole.MAIN }.map { it.id }.toSet()

    val usedExercises = done.map { it.exerciseId }.toSet() + workout.layout.blocks.flatMap { b -> b.items.map { it.exerciseId } }
    val history = previous
        .filter { it.workoutId != workout.id && it.done && !it.deleted && it.exerciseId in usedExercises }
        .groupBy { it.exerciseId to it.workoutId }
        .map { (key, sets) ->
            PreviousResult(key.first, name(key.first), key.second, sets.sortedBy { it.setNo }.map { it.toDone(key.first) })
        }

    return Comparison(
        workoutId = workout.id,
        planId = workout.planId,
        planVersion = workout.planVersion,
        startedAt = workout.startedAt,
        date = workout.date,
        finishedAt = workout.finishedAt,
        comment = workout.comment,
        bodyweightKg = bodyweight,
        heightCm = heightCm,
        measurements = measurements.filter { it.workoutId == workout.id && !it.deleted }.sortedBy { it.measuredAt },
        totals = Totals(
            durationSec = duration,
            setsPlanned = all.count { it.target != null },
            setsDone = all.count { it.status == SetStatus.DONE },
            setsSkipped = all.count { it.status == SetStatus.SKIPPED },
            setsAdded = all.count { it.status == SetStatus.ADDED },
            workSets = done.count { it.blockId in mainBlocks },
            volumeKg = volume,
            volumeUnknownSets = volumes.count { it == null },
            timeUnderLoadSec = done.sumOf { it.timeUnderLoad ?: 0 },
            paceSecPerKg = if (duration != null && volume > 0) duration / volume else null,
        ),
        blocks = blocks,
        previous = history,
        unplaced = done.filter { it.itemId !in placed }.map { it.toDone(null) },
    )
}

private fun SetFact.toDone(rowExercise: Uuid?) = DoneSet(
    setNo = setNo,
    recordType = recordType,
    perSide = perSide,
    weight = weight,
    reps = reps,
    extraWeight = extraWeight,
    seconds = seconds,
    rating = rating,
    note = note,
    doneAt = doneAt,
    exerciseId = exerciseId.takeIf { it != rowExercise },
)
