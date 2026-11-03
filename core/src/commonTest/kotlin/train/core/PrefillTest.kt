package train.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PrefillTest {
    private val press = exercise("Жим гантелей", implements = 2)
    private val rope = exercise("Скакалка", type = RecordType.TIME)

    @Test
    fun targetFirstThenLastFactOnSameSetNo() {
        val row1 = item(press, TargetSet(weight = 12.5), TargetSet(weight = 15.0, repsMin = 8, repsMax = 12))
        val w = workout(block(row1))
        val previous = listOf(w.fact(row1, 1, weight = 10.0, reps = 11), w.fact(row1, 2, weight = 12.5, reps = 9))
            .map { f -> f.copy(workoutId = newId()) }
        val rows = BlockPasses(w.layout.blocks[0], emptyList()).rows(row1)

        // Вес из плана, повторов в плане нет — из прошлого первого подхода
        assertEquals(SetValues(weight = 12.5, reps = 11), rows[0].values(previous))
        // Диапазон — верхняя граница; прошлое не нужно
        assertEquals(SetValues(weight = 15.0, reps = 12), rows[1].values(previous))
    }

    @Test
    fun nothingKnownStaysNull() {
        assertEquals(SetValues(), prefill(RecordType.WEIGHT_REPS, null, null))
        assertEquals(SetValues(), prefill(RecordType.WEIGHT_REPS, TargetSet(), null))
    }

    @Test
    fun onlyFieldsOfRecordType() {
        val last = SetFact(
            newId(), newId(), newId(), newId(), 1, exerciseId = rope.id, recordType = RecordType.REPS,
            reps = 100, seconds = 60, done = true,
        )
        // Скакалка была на повторы, теперь на время: повторы не тянутся
        assertEquals(SetValues(seconds = 60), prefill(RecordType.TIME, TargetSet(weight = 5.0), last))
        assertEquals(SetValues(reps = 100), prefill(RecordType.REPS, null, last))
    }

    @Test
    fun factValuesWinAndDoNotSpread() {
        val row1 = item(press, TargetSet(weight = 12.5, repsMax = 10), TargetSet(weight = 12.5, repsMax = 10))
        val w = workout(block(row1))
        val edited = w.fact(row1, 1, weight = 15.0, reps = 6, done = false)
        val rows = BlockPasses(w.layout.blocks[0], listOf(edited)).rows(row1)
        assertEquals(SetValues(weight = 15.0, reps = 6), rows[0].values(emptyList()))
        assertEquals(SetValues(weight = 12.5, reps = 10), rows[1].values(emptyList()))
    }

    @Test
    fun lastIgnoresOtherExercisesAndUndone() {
        val row1 = item(press, TargetSet())
        val w = workout(block(row1))
        val row = BlockPasses(w.layout.blocks[0], emptyList()).rows(row1)[0]
        val other = w.fact(row1, 1, weight = 40.0, reps = 5, exerciseId = rope.id)
        val undone = w.fact(row1, 1, weight = 30.0, reps = 5, done = false)
        assertEquals(SetValues(), row.values(listOf(other, undone)))
    }
}
