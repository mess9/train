package train.core

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

val T0 = Instant.parse("2026-11-03T10:00:00Z")

fun exercise(
    name: String,
    implements: Int = 1,
    type: RecordType = RecordType.WEIGHT_REPS,
    perSide: Boolean = false,
    bodyweightFactor: Double? = null,
) = Exercise(newId(), name, implements = implements, recordType = type, perSide = perSide, bodyweightFactor = bodyweightFactor)

fun item(ex: Exercise, vararg sets: TargetSet, restSec: Int? = 60) =
    LayoutItem(newId(), ex.id, ex.recordType, ex.perSide, sets.toList(), restSec)

fun block(vararg items: LayoutItem, restAfterPassSec: Int? = 120, role: BlockRole = BlockRole.MAIN) =
    LayoutBlock(newId(), role, items = items.toList(), restAfterPassSec = restAfterPassSec)

fun workout(vararg blocks: LayoutBlock) = Workout(newId(), startedAt = T0, layout = WorkoutLayout(blocks.toList()))

/** Факт подхода `setNo` строки `item`; `minute` — время отметки от старта. */
fun Workout.fact(
    item: LayoutItem,
    setNo: Int,
    weight: Double? = null,
    reps: Int? = null,
    seconds: Int? = null,
    done: Boolean = true,
    deleted: Boolean = false,
    minute: Int = setNo,
    exerciseId: Uuid = item.exerciseId,
): SetFact {
    val block = layout.blocks.first { b -> b.items.any { it.id == item.id } }
    return SetFact(
        id = newId(),
        workoutId = id,
        blockId = block.id,
        itemId = item.id,
        setNo = setNo,
        targetId = item.sets.getOrNull(setNo - 1)?.id,
        exerciseId = exerciseId,
        recordType = item.recordType,
        perSide = item.perSide,
        weight = weight,
        reps = reps,
        seconds = seconds,
        rating =if (done) Rating.NORMAL else null,
        done = done,
        doneAt = if (done) T0 + minute.minutes else null,
        deleted = deleted,
    )
}
