package train.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Раскладка тренировки. При старте — снимок целей версии плана, дальше меняется на ходу:
 * замена, добавление, порядок, тип записи для новых подходов. Цели внутри не меняются —
 * по ним идёт `compare`.
 */
@Serializable
data class WorkoutLayout(val blocks: List<LayoutBlock>)

@Serializable
data class LayoutBlock(
    val id: Uuid,
    val role: BlockRole,
    val title: String? = null,
    val items: List<LayoutItem>,
    val restAfterPassSec: Int? = null,
)

@Serializable
data class LayoutItem(
    val id: Uuid,
    val exerciseId: Uuid,
    /** Для новых подходов; сделанные хранят свой. */
    val recordType: RecordType,
    val perSide: Boolean,
    val sets: List<TargetSet>,
    val restSec: Int? = null,
    val note: String? = null,
    /** Упражнение заменено на ходу: было это. */
    val replacedFrom: Uuid? = null,
    /** Добавлено в тренировке, в плане не было. */
    val added: Boolean = false,
    /** Убрано из тренировки. Сделанные подходы остаются в факте. */
    val removed: Boolean = false,
)

/** Снимок плана для старта тренировки; тип записи и сторона — из справочника, если в плане нет. */
fun PlanBody.snapshot(exercise: (Uuid) -> Exercise?): WorkoutLayout = WorkoutLayout(
    blocks.map { block ->
        LayoutBlock(
            id = block.id,
            role = block.role,
            title = block.title,
            restAfterPassSec = block.restAfterPassSec,
            items = block.items.map { item ->
                val ex = exercise(item.exerciseId)
                LayoutItem(
                    id = item.id,
                    exerciseId = item.exerciseId,
                    recordType = item.recordType ?: ex?.recordType ?: RecordType.WEIGHT_REPS,
                    perSide = item.perSide ?: ex?.perSide ?: false,
                    sets = item.sets,
                    restSec = item.restSec,
                    note = item.note,
                )
            },
        )
    },
)

@Serializable
@SerialName("workout")
data class Workout(
    override val id: Uuid,
    val planId: Uuid? = null,
    val planVersion: Int? = null,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    /** Комментарий владельца для агента. */
    val comment: String? = null,
    val layout: WorkoutLayout,
    override val rev: Long = 0,
    override val deleted: Boolean = false,
) : SyncRecord {
    val active get() = finishedAt == null && !deleted

    override fun withRev(rev: Long) = copy(rev = rev)
}

@Serializable
data class SetValues(
    val weight: Double? = null,
    val reps: Int? = null,
    val extraWeight: Double? = null,
    val seconds: Int? = null,
)

/**
 * Фактический подход. Хранит свои упражнение, тип записи и сторону: его смысл не меняется при
 * правке раскладки или справочника (ADR 0006). Снятие отметки — `done = false`, удаление —
 * `deleted = true`.
 */
@Serializable
@SerialName("set")
data class SetFact(
    override val id: Uuid,
    val workoutId: Uuid,
    val blockId: Uuid,
    val itemId: Uuid,
    /** Номер подхода у упражнения, с 1; он же номер прохода. */
    val setNo: Int,
    /** Целевой подход из раскладки; у добавленного — `null`. */
    val targetId: Uuid? = null,
    val exerciseId: Uuid,
    val recordType: RecordType,
    val perSide: Boolean = false,
    val weight: Double? = null,
    val reps: Int? = null,
    val extraWeight: Double? = null,
    val seconds: Int? = null,
    val rating: Rating? = null,
    val note: String? = null,
    val done: Boolean = false,
    /** Время отметки по часам телефона: для порядка и отдыха, не для конфликтов. */
    val doneAt: Instant? = null,
    override val rev: Long = 0,
    override val deleted: Boolean = false,
) : SyncRecord {
    val values get() = SetValues(weight, reps, extraWeight, seconds)

    override fun withRev(rev: Long) = copy(rev = rev)
}

/**
 * Объём подхода, кг: (вес × снарядов + собственный вес × доля) × повторы, ×2 если «на сторону».
 * Доп. вес — как вес одного снаряда. `null` — объём неизвестен, а не ноль: нет повторов или веса,
 * доля задана, а собственного веса нет, или считать нечего (повторы без веса и без доли).
 * Подходы на время объёма не имеют — они во времени под нагрузкой.
 */
fun SetFact.volume(exercise: Exercise?, bodyweight: Double?): Double? {
    val r = reps ?: return null
    val external = when (recordType) {
        RecordType.WEIGHT_REPS -> (weight ?: return null) * (exercise?.implements ?: 1)
        RecordType.REPS -> extraWeight ?: 0.0
        RecordType.TIME, RecordType.WEIGHT_TIME -> return null
    }
    val factor = exercise?.bodyweightFactor
    val body = if (factor == null) 0.0 else (bodyweight ?: return null) * factor
    if (external + body == 0.0) return null
    return (external + body) * r * sides
}

/** Время подхода под нагрузкой, с; «на сторону» — на обе. */
val SetFact.timeUnderLoad: Int? get() = if (recordType.usesSeconds) seconds?.let { it * sides } else null

private val SetFact.sides get() = if (perSide) 2 else 1
