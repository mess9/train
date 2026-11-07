package train.core

import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompareTest {
    private val press = exercise("Жим гантелей", implements = 2)
    private val lunge = exercise("Выпады", implements = 2, perSide = true)
    private val oldRow = exercise("Тяга штанги")
    private val newRow = exercise("Тяга гантели")
    private val exercises = listOf(press, lunge, oldRow, newRow).associateBy { it.id }

    private val a = item(press, TargetSet(weight = 10.0, repsMax = 10), TargetSet(weight = 10.0, repsMax = 10))
    private val b = item(lunge, TargetSet(weight = 8.0, repsMax = 8), TargetSet(weight = 8.0, repsMax = 8))
    // Тягу штанги заменили на тягу гантели после первого подхода
    private val c = item(newRow, TargetSet(weight = 20.0, repsMax = 10), TargetSet(weight = 20.0, repsMax = 10))
        .copy(replacedFrom = oldRow.id)
    private val w = workout(block(a, b), block(c)).copy(finishedAt = T0 + 40.minutes, comment = "тяжело")

    private val facts = listOf(
        w.fact(a, 1, weight = 10.0, reps = 10),
        w.fact(a, 2, weight = 10.0, reps = 8),
        w.fact(a, 3, weight = 10.0, reps = 6),                     // добавленный
        w.fact(b, 1, weight = 8.0, reps = 8),
        w.fact(b, 2, weight = 8.0, reps = 8, done = false),       // снята отметка → пропущен
        w.fact(c, 1, weight = 40.0, reps = 10, exerciseId = oldRow.id),
        w.fact(c, 2, weight = 20.0, reps = 10),
        w.fact(c, 3, weight = 20.0, reps = 10, deleted = true),   // удалённый не считается
    )

    @Test
    fun setsByStatus() {
        val r = compare(w, facts, exercises)
        val (pa, pb) = r.blocks[0].items
        assertEquals(listOf(SetStatus.DONE, SetStatus.DONE, SetStatus.ADDED), pa.sets.map { it.status })
        assertNull(pa.sets[2].target)
        assertEquals(listOf(SetStatus.DONE, SetStatus.SKIPPED), pb.sets.map { it.status })
        assertNull(pb.sets[1].fact)
        assertEquals("Жим гантелей", pa.name)
    }

    @Test
    fun replacementKeepsOldExerciseOnEarlierSets() {
        val pc = compare(w, facts, exercises).blocks[1].items[0]
        assertEquals(oldRow.id, pc.replacedFrom)
        assertEquals(oldRow.id, pc.sets[0].fact?.exerciseId)
        assertNull(pc.sets[1].fact?.exerciseId)
        assertEquals(2, pc.sets.size)
    }

    @Test
    fun totals() {
        val t = compare(w, facts, exercises).totals
        assertEquals(6, t.setsPlanned)
        assertEquals(5, t.setsDone)
        assertEquals(1, t.setsSkipped)
        assertEquals(1, t.setsAdded)
        assertEquals(40 * 60L, t.durationSec)
        // Жим: 10×2×(10+8+6); выпады: 8×2×8×2 сторону; тяга: 40×10 + 20×10
        val volume = 10.0 * 2 * 24 + 8.0 * 2 * 8 * 2 + 40.0 * 10 + 20.0 * 10
        assertEquals(volume, t.volumeKg)
        assertEquals(2400 / volume, t.paceSecPerKg)
    }

    @Test
    fun unfinishedWorkoutLastsUntilLastMark() {
        val r = compare(w.copy(finishedAt = null), facts, exercises)
        assertEquals(3 * 60L, r.totals.durationSec)
    }

    @Test
    fun bodyweightGoesIntoVolume() {
        val pullUp = exercise("Подтягивания", type = RecordType.REPS, bodyweightFactor = 1.0)
        val pull = item(pullUp, TargetSet(repsMax = 8), TargetSet(repsMax = 8))
        val wk = workout(block(pull))
        val sets = listOf(wk.fact(pull, 1, reps = 8), wk.fact(pull, 2, reps = 6).copy(extraWeight = 10.0))
        val r = compare(wk, sets, mapOf(pullUp.id to pullUp), bodyweight = 80.0)
        assertEquals(80.0 * 8 + 90.0 * 6, r.totals.volumeKg)
        assertEquals(0, r.totals.volumeUnknownSets)
        assertEquals(80.0, r.bodyweightKg)
    }

    @Test
    fun unknownVolumeIsNotZero() {
        val pullUp = exercise("Подтягивания", type = RecordType.REPS, bodyweightFactor = 1.0)
        val pull = item(pullUp, TargetSet(repsMax = 8))
        val wk = workout(block(pull))
        // Замера веса нет — объём подтягиваний неизвестен, но подход засчитан
        val r = compare(wk, listOf(wk.fact(pull, 1, reps = 8)), mapOf(pullUp.id to pullUp))
        assertEquals(0.0, r.totals.volumeKg)
        assertEquals(1, r.totals.volumeUnknownSets)
        assertNull(r.totals.paceSecPerKg)
        assertEquals(1, r.totals.workSets)
    }

    @Test
    fun repsWithoutWeightOrShareAreNotUnknown() {
        // Скакалка на прыжки и скручивания: считать нечего — не звёздочка «нет взвешивания»
        val rope = exercise("Скакалка", type = RecordType.REPS)
        val pullUp = exercise("Подтягивания", type = RecordType.REPS, bodyweightFactor = 1.0)
        val jump = item(rope, TargetSet(repsMax = 100))
        val pull = item(pullUp, TargetSet(repsMax = 8))
        val wk = workout(block(jump, pull))
        val sets = listOf(wk.fact(jump, 1, reps = 105), wk.fact(pull, 1, reps = 8))
        val withWeight = compare(wk, sets, listOf(rope, pullUp).associateBy { it.id }, bodyweight = 60.0).totals
        assertEquals(60.0 * 8, withWeight.volumeKg)
        assertEquals(0, withWeight.volumeUnknownSets)
        // Без взвешивания неизвестны только подтягивания
        assertEquals(1, compare(wk, sets, listOf(rope, pullUp).associateBy { it.id }).totals.volumeUnknownSets)
    }

    @Test
    fun workSetsAndTimeUnderLoad() {
        val rope = exercise("Скакалка", type = RecordType.TIME)
        val plank = exercise("Боковая планка", type = RecordType.TIME, perSide = true)
        val warm = item(rope, TargetSet(seconds = 120))
        val main = item(plank, TargetSet(seconds = 30), TargetSet(seconds = 30))
        val wk = workout(block(warm, role = BlockRole.WARMUP), block(main))
        val sets = listOf(wk.fact(warm, 1, seconds = 120), wk.fact(main, 1, seconds = 30), wk.fact(main, 2, seconds = 25))
        val t = compare(wk, sets, listOf(rope, plank).associateBy { it.id }).totals
        assertEquals(2, t.workSets)
        assertEquals(120 + 2 * 30 + 2 * 25, t.timeUnderLoadSec)
        assertEquals(0, t.volumeUnknownSets)
    }

    @Test
    fun previousResultsGroupedByWorkout() {
        val past = workout(block(a)).let { pw -> listOf(pw.fact(a, 2, weight = 9.0, reps = 10), pw.fact(a, 1, weight = 9.0, reps = 10)) }
        val unrelated = exercise("Скакалка")
        val r = compare(w, facts, exercises, past + facts + w.fact(a, 1).copy(exerciseId = unrelated.id, workoutId = newId()))
        assertEquals(1, r.previous.size)
        val p = r.previous[0]
        assertEquals(press.id, p.exerciseId)
        assertEquals(listOf(1, 2), p.sets.map { it.setNo })
        assertTrue(p.sets.all { it.exerciseId == null })
    }

    @Test
    fun comparesAgainstSnapshotNotPlan() {
        // Факт без места в раскладке не теряется
        val stray = w.fact(a, 1).copy(itemId = newId())
        val r = compare(w, facts + stray, exercises)
        assertEquals(1, r.unplaced.size)
        assertEquals("тяжело", r.comment)
    }
}
